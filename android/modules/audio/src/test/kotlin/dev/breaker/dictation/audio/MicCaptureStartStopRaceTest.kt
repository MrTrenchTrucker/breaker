package dev.breaker.dictation.audio

import dev.breaker.dictation.audio.RaceTestSupport.FULL_TAKE_SAMPLES
import dev.breaker.dictation.audio.RaceTestSupport.GATE_RELEASE_MS
import dev.breaker.dictation.audio.RaceTestSupport.GATE_WAIT_MS
import dev.breaker.dictation.audio.RaceTestSupport.NO_OVERLAP_WINDOW_MS
import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window [MicCapture.start] opens inside [MicSource.open], and what a
 * [MicCapture.stop] that lands in it owes the caller.
 *
 * The class promises two things that this window is the only place can break:
 * that a stop which has RETURNED has stopped the capture, and that start, stop,
 * start again is a second full take. A device takes long enough to open — tens
 * of milliseconds on a phone, a permission prompt on a cold start — that the
 * window is real, and a stop landing in it is a legal pair of calls rather than
 * a caller mistake.
 */
class MicCaptureStartStopRaceTest {

    @Test
    fun `a stop that lands inside the device's opening stops the capture before it returns`() {
        // The residual hole a stop() that only looks for session threads cannot
        // close. While the device is opening there is no capture thread and no
        // dispatcher, so a stop finds nothing to take, and if it RETURNS before
        // it has dropped the session flag then it returns having stopped
        // nothing: isCapturing still reads true, and the next start() is refused
        // as "already running" by a session this very stop ended. From the
        // caller's side it did exactly what it asked, and got a capture it
        // cannot restart.
        //
        // So the racing open() is released about a fifth of a second AFTER the
        // stop comes back, and the assertions run at that moment rather than
        // after the open has come back. An assertion that waited for the open
        // would pass against the old code too, which is the whole point: the
        // state has to be read while the hole is still open.
        //
        // No sleep decides the outcome. The gate release is a third thread on a
        // timer, the "stop has returned" edge is a latch, and the only wait for
        // the take is a bounded poll that ends as soon as the frames land.
        val source = GatedSource(script = speech(FULL_TAKE_SAMPLES))
        val capture = MicCapture(source = source)
        val frames = CopyOnWriteArrayList<FloatArray>()

        Thread({ capture.start(AudioListener { frames.add(it) }) }, "race-starter").start()

        assertTrue(
            "the device was never reached by open(), so this test never got into " +
                "the window it is about",
            source.insideOpen.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        // The stop, and the edge that matters: everything after this latch is
        // read after stop() has RETURNED.
        val stopReturned = CountDownLatch(1)
        val capturingWhenStopReturned = AtomicReference<Boolean?>(null)
        Thread({
            capture.stop()
            capturingWhenStopReturned.set(capture.isCapturing)
            stopReturned.countDown()
        }, "race-stopper").start()

        assertTrue(
            "stop() never returned; the racing open() is holding the session",
            stopReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        Thread({
            Thread.sleep(GATE_RELEASE_MS)
            source.releaseOpen.countDown()
        }, "open-gate-release").start()

        // The claim, at the instant it is claimed. The value is read by the
        // stop thread itself and only published after it has been read, so the
        // latch below makes it a value and not an absence.
        val capturingAtStopReturn = capturingWhenStopReturned.get()
        assertNotNull(
            "the stop thread returned without recording what isCapturing read at " +
                "that moment, so the claim below cannot be made at all",
            capturingAtStopReturn,
        )
        assertFalse(
            "stop() returned and isCapturing still reads true, so the session it " +
                "tore down is still up: a capture the caller has been told it " +
                "stopped cannot be restarted, because the next start() is refused " +
                "as \"already running\" until the racing open() comes back " +
                "(${GATE_RELEASE_MS}ms away). The stop found no session thread to " +
                "take, because the device was still opening, so it has to drop " +
                "the flag on its way out regardless.",
            capturingAtStopReturn == true,
        )

        // And the caller can act on it: the start issued after that stop is a
        // full take, not a refusal and not a take that came up short.
        //
        // The wait for the take is a wait for the TAKE, not for a stretch of
        // time. A wall-clock poll decides the outcome by how fast the machine
        // is, so on a loaded runner it gives up on a take that was about to
        // land and the test fails for a reason that has nothing to do with
        // what it is about.
        //
        // The condition it waits on is the CUMULATIVE sample count, not a
        // frame count: the take is many frames, and a latch counted down once
        // per callback would release the wait on the FIRST frame, with the
        // take still short. So the counter is incremented by every frame and
        // the latch is counted down once the running total reaches the whole
        // take — the same total the assertion below is going to read, so the
        // wait ends exactly when the thing being waited for is true.
        //
        // The countdown happens once and only once. Every callback that
        // passes the threshold would otherwise count down, which is
        // harmless for a latch but is the wrong statement to leave behind:
        // this says "the take is complete" once, and only the first crossing
        // gets to say it. Both the running total and the once-only claim are
        // touched from the device thread while this thread waits, so both
        // are the counter's own.
        val startFailure = AtomicReference<Throwable?>(null)
        val takeArrived = CountDownLatch(1)
        val takeSamples = AtomicInteger(0)
        val takeCompleted = AtomicBoolean(false)
        Thread({
            try {
                capture.start(AudioListener { frame ->
                    frames.add(frame)
                    val total = takeSamples.addAndGet(frame.size)
                    if (total >= FULL_TAKE_SAMPLES && takeCompleted.compareAndSet(false, true)) {
                        takeArrived.countDown()
                    }
                })
            } catch (e: Throwable) {
                startFailure.set(e)
                takeArrived.countDown()
            }
        }, "race-restart").start()

        // Bounded, because the signal is not guaranteed to fire: if start()
        // itself threw, the catch above counts it down and the wait returns at
        // once rather than burning the whole bound; only a start() that died
        // without throwing (a hard kill, an Error, a thread that never
        // returns) attaches no listener and nothing will ever count this down,
        // and an unbounded wait would hang the suite instead of failing it.
        // Generous for the same reason it is finite — a take that is going to
        // arrive at all arrives on the device's own time, after the racing
        // open comes back, and WAIT_SECONDS is already the file's ceiling for
        // "the thing under test has had its chance".
        //
        // The result is deliberately discarded. This is the WAIT, not the
        // verdict: a timeout falls straight through to stop() and to the
        // assertions below, so a start() that threw is reported by the
        // assertNull that names it rather than by a generic "the take never
        // arrived" that would hide the failure this test exists to catch.
        takeArrived.await(WAIT_SECONDS, TimeUnit.SECONDS)
        capture.stop()

        assertNull(
            "start() after a stop that had returned threw: ${startFailure.get()}. " +
                "The stop ended a session, so the next start must not be told the " +
                "capture is already running",
            startFailure.get(),
        )
        assertEquals(
            "the take after the restart came to ${frames.sumOf { it.size }} samples " +
                "but the device produced $FULL_TAKE_SAMPLES, so it is not a full " +
                "take of its own",
            FULL_TAKE_SAMPLES,
            frames.sumOf { it.size },
        )
        assertNull(
            "the capture reported a failure for a take that ran cleanly: " +
                "${capture.failure}. Waiting for the previous device's open to " +
                "finish is not a failure",
            capture.failure,
        )
    }

    @Test
    fun `a start that passes the running flag while an earlier open is in flight waits for the device`() {
        // One microphone, one open at a time — on the path where two starts
        // genuinely overlap.
        //
        // The obvious way to test this does not test it. A second start issued
        // while the first is still CAPTURING is refused by the running flag in
        // MicCapture.start(), before it ever reaches openAndPublish, the device
        // gate or source.open(). So the check is vacuous: the microphone was
        // never shared because the second start never got near it, and the
        // assertion holds on code that has no device gate at all.
        //
        // What genuinely overlaps is a THIRD start, separated from the first by
        // a stop. The stop drops the running flag, so the third start passes the
        // flag and reaches openAndPublish with the FIRST start still inside its
        // own source.open(). The two are now at the device at the same moment,
        // and what has to hold them apart is the gate rather than the flag.
        //
        // The order is made deterministic rather than timed. The source holds
        // its open until this test releases it, so "the first start is inside
        // open()" is a latch rather than a hope, and the stop in the middle is
        // issued and observed to return before the third start exists at all.
        // Inside the source, every open's entry and exit is recorded against
        // the thread that made it and the peak overlap counted, so "the third
        // start did not enter open() until the first had left it" is read off
        // what the device saw rather than inferred from how long anyone slept.
        val source = GatedSource(script = speech(FULL_TAKE_SAMPLES))
        // Generous, so the third start's wait on the gate is bounded by this
        // test releasing the first open and not by the give-up firing. The first
        // open is held for the negative window and released straight after, so
        // this ceiling is never approached.
        val capture = MicCapture(source = source, joinTimeoutMs = GATE_WAIT_MS)

        val firstStart = AtomicReference<Throwable?>(null)
        val first = Thread({
            try {
                capture.start(AudioListener { })
            } catch (e: Throwable) {
                firstStart.set(e)
            }
        }, "c-open-first")
        first.start()
        assertTrue(
            "the first start never reached open(), so the race window never opened",
            source.insideOpen.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )

        // The stop in the middle. It has to RETURN, and it has to drop the
        // running flag — otherwise the third start is refused by the flag and
        // this test has measured the vacuous path it exists to replace. Both
        // are observed rather than assumed.
        val stopReturned = CountDownLatch(1)
        val capturingAfterStop = AtomicReference<Boolean?>(null)
        Thread({
            capture.stop()
            capturingAfterStop.set(capture.isCapturing)
            stopReturned.countDown()
        }, "c-open-stopper").start()
        assertTrue(
            "the stop() that landed inside the first open never returned; the " +
                "first start is still holding the device and there is no overlap " +
                "to test",
            stopReturned.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        assertEquals(
            "stop() returned while isCapturing still read true, so the running " +
                "flag was not dropped and the third start will be refused by the " +
                "flag instead of reaching the device",
            false,
            capturingAfterStop.get(),
        )

        // The third start. Its frames are collected, because a gate that
        // refused every start outright would satisfy the window below and
        // deliver nothing.
        val frames = CopyOnWriteArrayList<FloatArray>()
        val thirdStart = AtomicReference<Throwable?>(null)
        val thirdFinished = CountDownLatch(1)
        Thread({
            try {
                capture.start(AudioListener { frames.add(it) })
            } catch (e: Throwable) {
                thirdStart.set(e)
            }
            thirdFinished.countDown()
        }, "c-open-third").start()

        // A bounded window in which the third start must NOT reach the device:
        // the first is provably still inside its own open, so an open here is
        // two on one microphone. Polled rather than slept — the window ends the
        // moment a second open appears, so a bypassed gate is caught at once
        // instead of at the end of a fixed delay. Nothing here decides the
        // outcome; it only ends the window.
        val windowDeadline = System.currentTimeMillis() + NO_OVERLAP_WINDOW_MS
        while (source.openCalls.get() < 2 && System.currentTimeMillis() < windowDeadline) {
            Thread.sleep(2)
        }
        val opensDuringFirstOpen = source.openCalls.get()
        val firstOpenStillHeldIt = source.insideOpenInProgress

        // Now let the first open finish, and the third start have the device.
        source.releaseOpen.countDown()
        first.join(WAIT_SECONDS * 1000L)
        val takeDeadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (frames.sumOf { it.size } < FULL_TAKE_SAMPLES &&
            System.currentTimeMillis() < takeDeadline
        ) {
            Thread.sleep(5)
        }
        capture.stop()

        assertTrue(
            "the first start had already left open() before the window opened, so " +
                "there was never an overlap for the third start to miss and the " +
                "window proved nothing (events ${source.openEvents})",
            firstOpenStillHeldIt,
        )
        assertEquals(
            "the third start reached source.open() while the first was still inside " +
                "its own open: ${source.openCalls.get()} opens for two starts that both " +
                "got past the running flag, entry/exit order ${source.openEvents}. Two " +
                "opens in flight on one source means two captures reading it, and " +
                "whichever start loses the race to teardown closes the device out from " +
                "under the winner's take. The running flag cannot be what stopped this, " +
                "because the stop() above already dropped it and the third start got " +
                "past it.",
            1,
            opensDuringFirstOpen,
        )
        assertEquals(
            "the device saw a peak of ${source.peakOpenConcurrency.get()} opens inside " +
                "it at once (events ${source.openEvents}); one microphone has one open at " +
                "a time",
            1,
            source.peakOpenConcurrency.get(),
        )
        assertTrue(
            "the third start never finished, so the overlap check proved nothing",
            thirdFinished.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        assertNull(
            "the first start threw ${firstStart.get()}. A start whose device was taken " +
                "away by a stop has to unwind quietly rather than fault the caller for a " +
                "race it did not cause",
            firstStart.get(),
        )
        assertNull(
            "the third start() threw ${thirdStart.get()}. A stop separated it from the " +
                "first start, so it is a second full take and not a refusal",
            thirdStart.get(),
        )
        assertEquals(
            "the third start delivered ${frames.sumOf { it.size }} samples but the device " +
                "produced $FULL_TAKE_SAMPLES, so waiting for the gate cost it audio or it " +
                "never really opened the device at all",
            FULL_TAKE_SAMPLES,
            frames.sumOf { it.size },
        )
        assertNull(
            "the capture reported a failure for a take that ran cleanly: " +
                "${capture.failure}. Waiting for the previous open to finish is not a " +
                "failure, and a gate that reported the give-up instead of serialising " +
                "the opens would be failing a caller for waiting a moment",
            capture.failure,
        )
        assertFalse(
            "isCapturing is true after the third start's session was stopped",
            capture.isCapturing,
        )
    }

}
