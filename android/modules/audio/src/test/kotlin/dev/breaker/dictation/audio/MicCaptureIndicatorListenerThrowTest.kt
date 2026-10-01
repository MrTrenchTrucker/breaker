package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
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
 * What a mic-indicator listener that throws owes the capture.
 *
 * The indicator is not bookkeeping. It is the only way a screen learns the
 * microphone is live, and its promise is one-directional and absolute: a bound
 * screen never shows "not recording" while the microphone is open, and never
 * shows "recording" once it is shut. A listener that throws — on Android, a
 * view touched off the main thread does exactly this — breaks that promise
 * twice over: the exception escapes with the session still up, and the
 * `forEach` that raised it never reaches the listeners after it, so a screen
 * bound to one of THOSE is left showing the wrong state for a microphone that
 * really is live.
 *
 * Both halves are load-bearing. Guarding the call site so the exception cannot
 * escape fixes the strand and leaves the second listener un-notified; notifying
 * every listener before re-raising fixes the second and leaves the strand.
 */
class MicCaptureIndicatorListenerThrowTest {

    @Test
    fun `a listener that throws on the way to recording does not strand the capture`() {
        // The strand this whole file is about. markRecordingStarted() is called
        // BEFORE the try that wraps source.open(), so an exception from a
        // listener leaves start() with running = true and the indicator live:
        // every later start() is refused as "already running", and every stop()
        // finds no session threads and returns, so the capture is stuck until
        // the process dies.
        //
        // Asserted as a BALANCE, not a single reading: after the throw the
        // device must not be open with no session to stop, and a start issued
        // after the offending listener has been removed must deliver a full
        // take. A capture that merely reported isCapturing == false while
        // leaving the device open would pass the first and fail the second.
        val script = silenceThenSpeech(totalMs = 400)
        // holdsOpenWhenScriptSpent, because this test reads the take to its
        // LAST sample before it stops: a device that reports nothing the moment
        // its script is spent makes the capture conclude on its own with a
        // give-up failure about silence, and that failure is recorded in the
        // very field the last assertion below reads as null. The assertion is
        // about the listener — "recovering from one broken listener is not
        // itself a failure" — so the device must not supply a second, unrelated
        // one while the take is being collected.
        val source = FakeMicSource(script = script, holdsOpenWhenScriptSpent = true)
        val indicator = RecordingIndicator()
        val capture = MicCapture(source = source, indicator = indicator)

        val threw = IllegalStateException("the view was touched off the main thread")
        // Throws only on the way UP. addListener calls the listener once
        // immediately with the current value, so a listener that threw on every
        // call would fault here in the fixture rather than inside start(), and
        // the test would be measuring its own setup. "recording" is the
        // transition that strands the capture, so that is the one it faults on.
        val offender: (Boolean) -> Unit = { recording ->
            if (recording) throw threw
        }
        indicator.addListener(offender)

        val startFailure = AtomicReference<Throwable?>(null)
        try {
            capture.start(AudioListener { })
            startFailure.set(null)
        } catch (e: Throwable) {
            startFailure.set(e)
        }

        // Whatever the caller's side looks like, the session cannot be left up.
        // Read as a balance so a "fixed" state that is really the strand cannot
        // pass: the flag down, the indicator down, and no device open with
        // nothing holding it.
        val stranded = "start threw ${startFailure.get()}, isCapturing " +
            "${capture.isCapturing}, indicator live ${indicator.isRecording}, " +
            "device open ${source.open}, opens ${source.openCalls}, " +
            "closes ${source.closeCalls}"

        assertFalse(
            "a throwing indicator listener left the session up: $stranded. " +
                "isCapturing true means every later start() is refused as " +
                "\"already running\" and every stop() finds no threads to join, " +
                "so the capture is stuck until the process dies",
            capture.isCapturing,
        )
        assertFalse(
            "a throwing indicator listener left the indicator live: $stranded. " +
                "The microphone was never opened by a session that survived, so a " +
                "bound screen shows \"recording\" for a microphone nobody holds",
            indicator.isRecording,
        )
        assertFalse(
            "a device was left open with no session holding it: $stranded",
            source.open,
        )
        assertEquals(
            "the device was opened but never closed: $stranded",
            0,
            source.closeCalls,
        )

        // And the exception reached somewhere real. Not swallowed: a caller that
        // only saw a null would believe the microphone opened cleanly.
        assertNotNull(
            "the listener's exception reached nobody: $stranded. It must be " +
                "reported to the caller of start() and recorded in failure, so " +
                "a broken listener is visible rather than silent",
            capture.failure,
        )
        assertTrue(
            "the recorded failure is not the listener's own: $stranded. It was " +
                "${capture.failure}",
            capture.failure === threw || capture.failure!!.message!!.contains("main thread"),
        )

        // The caller can recover: remove the broken listener and the next start
        // is a full take, not a refusal and not a take that came up short.
        indicator.removeListener(offender)
        val frames = CopyOnWriteArrayList<FloatArray>()
        capture.start(AudioListener { frames.add(it) })
        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (frames.sumOf { it.size } < script.size &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(5)
        }
        capture.stop()

        assertEquals(
            "the start after removing the throwing listener came to " +
                "${frames.sumOf { it.size }} samples but the device produced " +
                "the whole take, so the strand outlived the offending call: " +
                "a capture a caller cannot restart is one they never get back",
            // The whole of the script, read off the fixture rather than restated
            // as a frame count: 400 ms at 16 kHz is 6400 samples, which is 20
            // frames of 320 — the earlier 4-frame constant named a take five
            // times shorter than the device the test actually opened, so it
            // could not be satisfied by a capture that delivered the whole
            // thing. Read off the script it can only ever be right.
            script.size,
            frames.sumOf { it.size },
        )
        assertNull(
            "the capture reported a failure for a take that ran cleanly: " +
                "${capture.failure}. Recovering from one broken listener is not " +
                "itself a failure",
            capture.failure,
        )
    }

    @Test
    fun `every listener is told even when an earlier one throws`() {
        // The indicator's own promise is broken by the forEach this replaces:
        // it halts at the FIRST throwing listener, so a screen bound to a
        // listener registered after it is never told the microphone went live,
        // and is never told it stopped either. That screen shows "not
        // recording" for a live microphone, which is the exact abuse the
        // indicator exists to prevent.
        //
        // Both directions, because both are forEach over the same list: a
        // fix that only guards the way to true leaves the way to false broken,
        // and a screen that missed the true never sees the false either.
        val indicator = RecordingIndicator()
        val secondSaw = CopyOnWriteArrayList<Boolean>()
        val firstThrew = AtomicReference<Throwable?>(null)

        // Throws only on the way up, for the same reason as the other test: the
        // immediate call addListener makes would otherwise fault in the fixture
        // instead of inside markRecordingStarted, and the point here is what a
        // LATER listener is told, not how this one was registered.
        indicator.addListener { recording ->
            if (recording) throw IllegalStateException("the first listener is broken")
        }
        indicator.addListener { secondSaw.add(it) }

        val startFailure = AtomicReference<Throwable?>(null)
        val capture = MicCapture(
            source = FakeMicSource(script = silenceThenSpeech(totalMs = 400)),
            indicator = indicator,
        )
        try {
            capture.start(AudioListener { })
        } catch (e: Throwable) {
            startFailure.set(e)
        }
        firstThrew.set(startFailure.get())
        capture.stop()

        assertNotNull(
            "the first listener's exception reached nobody. It must not be " +
                "swallowed, or a broken listener is indistinguishable from a " +
                "screen that simply was not notified",
            firstThrew.get(),
        )
        assertEquals(
            "the listener registered AFTER the throwing one was told " +
                "[$secondSaw]. It must be told true on the way up and false on " +
                "the way down, whatever an earlier listener did: the forEach " +
                "halting at the first throw is what left this screen showing " +
                "\"not recording\" for a live microphone",
            // The registration call counts, and it is the contract rather than
            // an artefact: addListener tells a new listener the current value
            // immediately, so a screen binding after a mark is not left showing
            // the previous state until the next transition. So this listener
            // saw false (registered while dark), then true (the way up, past
            // the throwing one), then false (the way down). Asserting the list
            // from the first registration is what makes the middle entry a
            // statement about the forEach: dropping it is exactly the bug.
            listOf(false, true, false),
            secondSaw.toList(),
        )
        assertFalse(
            "the indicator is still live after start() and stop() with a " +
                "throwing listener in front",
            indicator.isRecording,
        )
    }

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
        }, "r4-outer-stop").start()

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
        }, "t2-stop").start()

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

    private companion object {
        /**
         * Long enough that the re-entered stop() has to wait to be caught, short
         * enough that a test which stalls on it says so quickly.
         *
         * The unfixed code spends exactly this on the inner stop, which is what
         * the elapsed-time assertion is calibrated against.
         */
        const val JOIN_TIMEOUT_MS = 2_000L

        /**
         * The join bound for the test that needs a thread to STILL be running
         * when the join gives up.
         *
         * Not [JOIN_TIMEOUT_MS]: this test's whole claim is about a dispatcher
         * that outlives its join, so it parks that dispatcher and then pays this
         * to find out. Two seconds of dead wait would prove the same thing four
         * times as slowly, and a test that spends two seconds proving a
         * teardown is stuck is a test nobody runs twice.
         */
        const val STUCK_JOIN_TIMEOUT_MS = 300L
    }
}
