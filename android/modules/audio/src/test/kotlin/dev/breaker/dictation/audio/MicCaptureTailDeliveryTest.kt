package dev.breaker.dictation.audio

import dev.breaker.dictation.audio.RaceTestSupport.FULL_TAKE_SAMPLES
import dev.breaker.dictation.audio.RaceTestSupport.GATE_WAIT_MS
import dev.breaker.dictation.audio.RaceTestSupport.NO_OVERLAP_WINDOW_MS
import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tail of a take: what a [MicCapture.stop] that lands after the device has
 * handed over the whole script owes the listener.
 *
 * The pre-stop assert is the deterministic fact this test exists to pin: at the
 * instant the device reports the script spent, the take is NOT yet whole on the
 * listener side — the resampler holds its half-kernel and the dispatcher holds
 * the last partial frame — so the stop is what completes it. A poll of the wall
 * clock can only observe that by burning its bound; waiting on the device signal
 * makes it a fact of the pipeline rather than of the machine.
 */
class MicCaptureTailDeliveryTest {

    @Test
    fun `a pre-stop signal fires before the take is whole on the listener side`() {
        // The same start/stop race as the waits-for-the-device test in
        // MicCaptureStartStopRaceTest, with one change: instead of polling the
        // wall clock it waits on the device signal source.scriptSpent, then
        // asserts BEFORE the stop that the listener's count is still below
        // FULL_TAKE_SAMPLES. That holds on every machine, because it is a fact of
        // the pipeline: at the scriptSpent instant the capture thread has written
        // 1265 samples to the ring (1280 - 15 held by the resampler's half-kernel)
        // and the dispatch thread is holding its last partial frame, so the
        // listener cannot be whole before the stop.
        val source = GatedSource(script = speech(FULL_TAKE_SAMPLES))
        val capture = MicCapture(source = source, joinTimeoutMs = GATE_WAIT_MS)

        val firstStart = AtomicReference<Throwable?>(null)
        val first = Thread({
            try {
                capture.start(AudioListener { })
            } catch (e: Throwable) {
                firstStart.set(e)
            }
        }, "d-starter-first")
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
        Thread({ capture.stop(); capturingAfterStop.set(capture.isCapturing); stopReturned.countDown() }, "d-stopper").start()
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
        }, "d-starter-third").start()

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

        // The device has handed over the whole script (a pre-stop signal): the
        // take is over on the device side, but not yet whole on the listener
        // side — the resampler holds its half-kernel and the dispatcher holds
        // the last partial frame until the stop drains them.
        assertTrue(
            "the device never handed over the whole take, so the test is waiting " +
                "on a script it never produced",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        // PRE-STOP: the take is not whole. This is the deterministic fact the
        // old wall-clock poll could only observe by burning its 10 s bound —
        // it waited for a count the next line's stop() was going to make.
        val preStop = frames.sumOf { it.size }
        assertTrue(
            "the pre-stop count reached the whole take ($preStop of $FULL_TAKE_SAMPLES), " +
                "so the take was already whole before the stop and the drain is not " +
                "what completes it",
            preStop < FULL_TAKE_SAMPLES,
        )
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
