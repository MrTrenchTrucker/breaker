package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A caller's stop while the capture thread is inside a device read.
 *
 * On a real phone the stop closes the device under that read, the read fails,
 * and the take must still end as a clean stop: no failure recorded, so the app
 * never says it could not finish listening just because the user stopped.
 * A failure with no stop requested must still be recorded.
 */
class MicCaptureStopDuringReadTest {

    @Test
    fun `a stop that fails the read in flight with an exception ends the take cleanly`() {
        // A failure means the stop closed the device under a read, the read
        // threw, and the capture recorded that as a fault: the app would show
        // an error on every ordinary stop.
        val source = ThrowingAfterCloseMicSource(ThrowingAfterCloseMicSource.AfterClose.THROW)
        val indicator = RecordingIndicator()
        val capture = MicCapture(source = source, indicator = indicator)

        startAndStopInsideRead(capture, source)

        assertCleanStop(capture, source, indicator)
    }

    @Test
    fun `a stop that fails the read in flight with an error code ends the take cleanly`() {
        // A failure means a negative code returned after the stop closed the
        // device was recorded as a driver fault.
        val source = ThrowingAfterCloseMicSource(ThrowingAfterCloseMicSource.AfterClose.NEGATIVE_CODE)
        val indicator = RecordingIndicator()
        val capture = MicCapture(source = source, indicator = indicator)

        startAndStopInsideRead(capture, source)

        assertCleanStop(capture, source, indicator)
    }

    @Test
    fun `a read that fails with no stop requested is still recorded`() {
        // Control. A failure means the clean-end rule swallowed a real device
        // fault when nobody had asked to stop.
        val source = ThrowingAfterCloseMicSource(ThrowingAfterCloseMicSource.AfterClose.THROW)
        source.failImmediately = true
        val capture = MicCapture(source = source)

        capture.start(AudioListener { })
        awaitFailure(capture)
        capture.stop()

        val failure = capture.failure
        assertNotNull("audio: a read that failed with no stop requested was not recorded", failure)
        assertEquals(
            "audio: the recorded failure was not the device fault",
            ThrowingAfterCloseMicSource.GENUINE_FAILURE,
            failure!!.message,
        )
    }

    @Test
    fun `an error code with no stop requested is still recorded`() {
        // Control for the error-code half, driven by the existing fake: a
        // device that reports -3 on its own is a fault, not a stop.
        val source = FakeMicSource(script = speech(320))
        source.failAfterReads = 1
        source.readErrorCode = -3
        val capture = MicCapture(source = source)

        capture.start(AudioListener { })
        awaitFailure(capture)
        capture.stop()

        val failure = capture.failure
        assertNotNull("audio: an error code with no stop requested was not recorded", failure)
        assertTrue(
            "audio: the recorded failure should name the code -3, was: ${failure!!.message}",
            failure.message!!.contains("-3"),
        )
    }

    @Test
    fun `the clean end of one take does not hide the real failure of the next`() {
        // A failure means the stop-suppression leaked: the second take's own
        // device fault was lost, or the first take's end left a stale state.
        val source = ThrowingAfterCloseMicSource(ThrowingAfterCloseMicSource.AfterClose.THROW)
        val indicator = RecordingIndicator()
        val capture = MicCapture(source = source, indicator = indicator)

        startAndStopInsideRead(capture, source)
        assertCleanStop(capture, source, indicator)

        source.failImmediately = true
        capture.start(AudioListener { })
        awaitFailure(capture)
        capture.stop()

        val failure = capture.failure
        assertNotNull("audio: the second take's real failure was lost after a clean stop", failure)
        assertEquals(
            "audio: the second take recorded the wrong failure",
            ThrowingAfterCloseMicSource.GENUINE_FAILURE,
            failure!!.message,
        )
    }

    @Test
    fun `a fault that the capture only observes after a stop was requested is a clean end`() {
        // Expected behaviour, stated on purpose: the device fault is thrown
        // while no stop is requested, but it is still in flight when the
        // caller's stop lands, and the capture observes it afterwards. The
        // caller asked to end the take, so the end is clean and the fault is
        // not recorded. Asserted: failure is null and the capture stopped.
        // A failure means the check looked at when the fault was thrown rather
        // than at whether a stop had been requested by the time it was seen.
        val source = ThrowingAfterCloseMicSource(ThrowingAfterCloseMicSource.AfterClose.THROW)
        source.failImmediately = true
        source.holdFailureUntilClosed = true
        val indicator = RecordingIndicator()
        val capture = MicCapture(source = source, indicator = indicator)

        startAndStopInsideRead(capture, source)

        assertCleanStop(capture, source, indicator)
    }

    private fun startAndStopInsideRead(
        capture: MicCapture,
        source: ThrowingAfterCloseMicSource,
    ) {
        capture.start(AudioListener { })
        awaitSignal(source.insideRead, "the capture thread to be inside read()")
        capture.stop()
    }

    private fun assertCleanStop(
        capture: MicCapture,
        source: ThrowingAfterCloseMicSource,
        indicator: RecordingIndicator,
    ) {
        assertNull(
            "audio: a stop during a read was recorded as a failure: ${capture.failure}",
            capture.failure,
        )
        assertFalse("audio: the capture was still capturing after stop()", capture.isCapturing)
        assertFalse("audio: the device was still open after stop()", source.isOpen)
        assertTrue("audio: stop() never closed the device", source.closeCalls >= 1)
        assertFalse("audio: the indicator was still live after stop()", indicator.isRecording)
    }

    private fun awaitSignal(latch: CountDownLatch, what: String) {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            fail("audio: timed out after ${WAIT_SECONDS}s waiting for $what")
        }
    }

    private fun awaitFailure(capture: MicCapture) {
        val deadline = System.currentTimeMillis() + SELF_END_TIMEOUT_MS
        while (capture.failure == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(2)
        }
        assertNotNull(
            "audio: no failure appeared within ${SELF_END_TIMEOUT_MS}ms",
            capture.failure,
        )
    }
}
