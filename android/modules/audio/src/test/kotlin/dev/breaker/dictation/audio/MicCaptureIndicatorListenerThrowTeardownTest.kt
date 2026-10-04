package dev.breaker.dictation.audio

import dev.breaker.dictation.audio.IndicatorTestSupport.JOIN_TIMEOUT_MS
import dev.breaker.dictation.audio.IndicatorTestSupport.STUCK_JOIN_TIMEOUT_MS
import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a throwing or re-entrant indicator listener costs the capture's
 * teardown, and what the teardown owes its caller when there is one.
 *
 * The two tests here are the teardown half of the promise described in
 * MicCaptureIndicatorListenerThrowTest, and they are about ORDER: a listener
 * running on the teardown's own thread, or throwing partway through it, must
 * not be able to eat the one record that says the microphone may still be
 * open.
 */
class MicCaptureIndicatorListenerThrowTeardownTest {

    @Test
    fun `a stop from a stopped listener returns at once instead of waiting on its own teardown`() {
        // Re-entrancy that the indicator's own threading creates for free.
        //
        // The indicator calls its listeners on the thread that made the change.
        // So a listener that calls stop() from inside markRecordingStopped() —
        // a legal thing to write, and a natural one for a screen that releases
        // something when the microphone goes dark — runs on the OUTER stop's
        // thread, inside the outer stop, partway through its teardown.
        //
        // The inner stop() finds teardownInFlight non-null, because that IS the
        // teardown it is running inside. Before the fix it took that latch and
        // awaited it — but the latch is counted down in the outer stop's
        // finally, which cannot run until this very call stack returns. So the
        // inner stop burned the whole joinTimeoutMs and then recorded "another
        // stop() was still tearing the session down": a failure about a second
        // stop that does not exist, invented by a caller who made exactly one.
        //
        // The fix is a same-thread ownership check, so the claim is that a
        // re-entered stop() returns at once and says nothing.
        val script = silenceThenSpeech(totalMs = 400)
        // holdsOpenWhenScriptSpent, because the failure field below is asserted
        // null and a device that reported silence the moment its script was
        // spent would supply an unrelated give-up failure of its own.
        val source = FakeMicSource(script = script, holdsOpenWhenScriptSpent = true)
        val indicator = RecordingIndicator()
        val capture = MicCapture(source = source, indicator = indicator, joinTimeoutMs = JOIN_TIMEOUT_MS)

        val innerStopElapsedMs = AtomicReference<Long?>(null)
        val innerStopCalls = AtomicInteger(0)
        // Fires on the way DOWN only. addListener calls the listener once
        // immediately with the current value, so a listener that fired on every
        // call would be entered here in the fixture rather than inside stop(),
        // and the test would be measuring its own setup.
        // addListener calls the listener once IMMEDIATELY with the current
        // value, so the first invocation is the fixture telling this listener
        // what it already is, not a transition. Only later invocations are a
        // mark, and only the way down is the re-entry under test.
        val invocations = AtomicInteger(0)
        indicator.addListener { recording ->
            if (invocations.getAndIncrement() == 0) return@addListener
            if (recording) return@addListener
            val began = System.nanoTime()
            capture.stop()
            val e2 = (System.nanoTime() - began) / 1_000_000
            innerStopElapsedMs.set(e2)
            innerStopCalls.incrementAndGet()
        }

        capture.start(AudioListener { })

        val outerReturned = CountDownLatch(1)
        val outerFailure = AtomicReference<Throwable?>(null)
        Thread({
            try {
                capture.stop()
            } catch (e: Throwable) {
                outerFailure.set(e)
            }
            outerReturned.countDown()
        }, "outer-stop").start()

        assertTrue(
            "the outer stop() never returned; a listener that calls stop() from " +
                "inside markRecordingStopped() is deadlocking the teardown that " +
                "called it",
            outerReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        val elapsed = innerStopElapsedMs.get()
        assertNotNull(
            "the indicator listener's stop() never ran at all (calls " +
                "${innerStopCalls.get()}), so the listener was not told the " +
                "microphone went dark and this test measured nothing",
            elapsed,
        )
                assertNull(
            "the outer stop() threw ${outerFailure.get()}. A listener calling stop() " +
                "from inside markRecordingStopped() is a legal re-entry, not an error, " +
                "and must not fault the caller that stopped the capture",
            outerFailure.get(),
        )
        // The claim, on the clock. Generous but bounded, and deliberately a
        // fraction of joinTimeoutMs rather than a near-miss against it: the
        // unfixed code spends the FULL joinTimeoutMs here, so a ceiling of a
        // quarter separates the two with room for a loaded machine and cannot be
        // met by a stop that merely finished a little late.
        assertTrue(
            "the stop() called from inside markRecordingStopped() took ${elapsed}ms. It " +
                "runs on the outer stop's own thread, so the latch it would have " +
                "awaited is counted down by the teardown it is INSIDE, and that " +
                "teardown cannot finish until this call returns. A stop that " +
                "re-enters its own teardown has nothing to wait for and nothing to " +
                "report, so it must return at once. The ceiling is joinTimeoutMs/4 = " +
                "${JOIN_TIMEOUT_MS / 4}ms.",
            elapsed!! < JOIN_TIMEOUT_MS / 4,
        )

        // And the outer stop really did the whole teardown, rather than being
        // cut short by the re-entry or run a second time by it.
        assertEquals(
            "the device was closed ${source.closeCalls} times across one outer stop(). " +
                "A re-entrant stop() must not run a second teardown, and must not " +
                "suppress the first one either",
            1,
            source.closeCalls,
        )
        assertFalse(
            "isCapturing is still true after the outer stop() returned, so a caller " +
                "that was told the capture stopped is holding one it cannot restart",
            capture.isCapturing,
        )
        assertFalse(
            "the indicator is still live after the outer stop() returned",
            indicator.isRecording,
        )
        assertNull(
            "the capture recorded ${capture.failure} for a stop that completed " +
                "cleanly. The inner stop() ran on the outer stop's own thread, so there " +
                "was no OTHER teardown for it to time out behind -- recording \"another " +
                "stop() was still tearing the session down\" here is a false report " +
                "about a stop that does not exist, and it is worse than silence because " +
                "a caller reads it as a real microphone problem",
            capture.failure,
        )
    }

    @Test
    fun `a throwing listener on the way down does not cost the teardown its stuck-thread record`() {
        // The two records this teardown owes a caller, and the order between
        // them.
        //
        // stop() has two things to say when a session does not shut: the
        // session thread(s) that were still running after joinTimeoutMs (a
        // microphone that may still be open), and the exception from a listener
        // the indicator called on the way down. Both are true, and both have to
        // survive.
        //
        // The listener is the one that can eat the other. markRecordingStopped()
        // calls out of this module, so an exception from it arrives at the
        // exact point where the stuck-thread record is about to be made. Rethrown
        // there, it leaves the finally below as the only thing that runs: close
        // and the joins happened, but the ONE record that says the microphone
        // may still be open is never made, and the caller's stack trace is
        // about a broken view instead. Nothing else reports it — a dispatcher
        // parked in onFrame is not going to notice its own teardown was
        // abandoned.
        //
        // So the contract is ORDER: the record about the session is made and
        // claims the field, and only then is the listener's exception raised on
        // the caller's side. The caller learns both — one by reading failure,
        // one by catching — and the field holds the one that describes the
        // microphone, not the one that describes the screen.
        val script = silenceThenSpeech(totalMs = 400)
        // holdsOpenWhenScriptSpent, because the assertions below read failure
        // and this test needs every word of it to be about the stuck dispatcher.
        // A device that reported the moment its script was spent would add a
        // give-up failure of its own and could win the field.
        val source = FakeMicSource(script = script, holdsOpenWhenScriptSpent = true)
        val indicator = RecordingIndicator()
        val capture = MicCapture(
            source = source,
            indicator = indicator,
            joinTimeoutMs = STUCK_JOIN_TIMEOUT_MS,
        )

        val release = CountDownLatch(1)
        val parked = CountDownLatch(1)
        // Parks the dispatcher inside onFrame and stays there until this test
        // releases it, so the thread stop() is about to join is provably alive
        // at the end of that join. That is what makes the stuck-thread record a
        // fact about this teardown rather than about how busy the machine was.
        capture.start(AudioListener {
            parked.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
        })
        assertTrue(
            "the listener was never reached, so there is no parked dispatcher " +
                "for stop() to give up on and this test is not in the state it " +
                "is about",
            parked.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        val threw = IllegalStateException("the view was touched off the main thread")
        // Throws only on the way DOWN, inside stop()'s markRecordingStopped().
        // addListener calls the listener once immediately with the current
        // value, so a listener that threw on every call would fault here in the
        // fixture rather than in the teardown, and the test would be measuring
        // its own setup. The way down is the one that collides with the record.
        val invocations = AtomicInteger(0)
        indicator.addListener { recording ->
            if (invocations.getAndIncrement() == 0) return@addListener
            if (recording) return@addListener
            throw threw
        }

        val stopFailure = AtomicReference<Throwable?>(null)
        val returned = CountDownLatch(1)
        Thread({
            try {
                capture.stop()
            } catch (e: Throwable) {
                stopFailure.set(e)
            }
            returned.countDown()
        }, "listener-stop").start()

        assertTrue(
            "stop() never returned; it is parked on a dispatcher this test has " +
                "not released yet, and a teardown that cannot finish is the " +
                "fault this test exists to catch",
            returned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        // Only now, with the teardown over: a dispatcher that outlives its
        // stop() would otherwise be reading a device the next test is opening.
        release.countDown()

        // The record about the session is in the field, and it is the one there.
        assertNotNull(
            "stop() returned with no failure recorded even though the parked " +
                "dispatcher outlived the ${STUCK_JOIN_TIMEOUT_MS}ms join. That " +
                "record is the only thing a caller has that says the " +
                "microphone may still be open",
            capture.failure,
        )
        assertTrue(
            "the recorded failure is ${capture.failure}, which is not the " +
                "stuck-session-thread record. A listener that threw on the way " +
                "down took the field with it, so the one failure that describes " +
                "the MICROPHONE was replaced by one that describes a view",
            capture.failure!!.message!!.contains(
                "capture did not stop within ${STUCK_JOIN_TIMEOUT_MS}ms",
            ),
        )

        // And the listener's exception still reached stop()'s caller. Held, not
        // swallowed, and not raised at the cost of the record above.
        assertTrue(
            "stop() threw ${stopFailure.get()} rather than the listener's own " +
                "exception. A broken listener must stay visible: a caller that " +
                "only saw a null would believe the indicator was told cleanly",
            stopFailure.get() === threw,
        )

        // The rest of the teardown is untouched by the throw: one close, and no
        // second teardown run against a session the first finished with.
        assertEquals(
            "the device was closed ${source.closeCalls} times across one " +
                "stop(). A listener throwing partway through the teardown must " +
                "not release the same resources twice",
            1,
            source.closeCalls,
        )
        assertFalse(
            "isCapturing is still true after stop() returned, so a caller told " +
                "the capture stopped is holding one it cannot restart",
            capture.isCapturing,
        )
        assertFalse(
            "the indicator is still live after stop() returned, so a bound " +
                "screen shows a live microphone that this stop() already shut",
            indicator.isRecording,
        )
    }
}
