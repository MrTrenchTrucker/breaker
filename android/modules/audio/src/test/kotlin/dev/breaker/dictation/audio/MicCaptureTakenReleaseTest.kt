package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A take the platform takes: another app or a call takes the microphone, and
 * the capture must release the device at once (close + indicator dark) and
 * report the take through the existing end-of-take path.
 *
 * A failure means the device is not released at all, is released without the
 * reason, is closed twice, drops the audio captured before the silence, or
 * leaks the taken path into other self-end reasons.
 */
class MicCaptureTakenReleaseTest {

    @Test
    fun `a take the platform takes ends at once with the taken reason`() {
        val source = FakeMicSource(script = speech(3200), holdsOpenWhenScriptSpent = true)
        source.takenAfterReads = 2
        val indicator = RecordingIndicator()
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, indicator = indicator, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        // stop() is deliberately not called: the end must arrive by itself.
        recorder.awaitEnd("a take the platform takes")

        val reported = recorder.endFailures[0]
        assertTrue(
            "audio: the end call got $reported, not the taken reason",
            reported is MicSourceException,
        )
        assertEquals(
            "audio: the end failure must report the taken reason",
            MicSourceException.Reason.MICROPHONE_TAKEN,
            (reported as MicSourceException).reason,
        )
        assertSame(
            "audio: the end call and MicCapture.failure disagree",
            capture.failure,
            reported,
        )
    }

    @Test
    fun `the released device is closed exactly once and a later stop does not close it again`() {
        val source = FakeMicSource(script = speech(3200), holdsOpenWhenScriptSpent = true)
        source.takenAfterReads = 2
        val indicator = RecordingIndicator()
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, indicator = indicator, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        recorder.awaitEnd("a take the platform takes")
        assertEquals(
            "audio: the released device must be closed exactly once",
            1,
            source.closeCalls,
        )
        capture.stop()
        assertEquals(
            "audio: a later stop() must not close the device again",
            1,
            source.closeCalls,
        )
    }

    @Test
    fun `the audio captured before the silence stays in the take`() {
        val source = FakeMicSource(script = speech(3200), holdsOpenWhenScriptSpent = true)
        source.takenAfterReads = 2
        val indicator = RecordingIndicator()
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, indicator = indicator, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        recorder.awaitEnd("a take the platform takes")
        // The first two reads succeeded (takenAfterReads = 2 means the third read
        // throws). The capture reads the device into a buffer four frames wide
        // (CaptureSessionLifecycle's READ_BUFFER_MULTIPLIER), so one successful
        // read hands the pipeline 4 frames; two full reads are 8 whole frames of
        // MicCapture.DEFAULT_FRAME_SAMPLES samples each, and all 8 arrive before
        // the take ends on the third read.
        val frames = recorder.events.count { it == TakeEndRecorder.FRAME_EVENT }
        val readBufferFrames = 4
        val expectedFrames = 2 * readBufferFrames
        assertEquals(
            "audio: the audio captured before the silence must stay in the take, got $frames frames",
            expectedFrames,
            frames,
        )
    }

    @Test
    fun `the drained tail arrives before the end`() {
        // When the platform takes the mic mid-take, the capture must still write the drained tail
        // (pipeline.drainTail()) to the ring before the end call. The tail is the resampler's
        // held-back fraction of a frame (15 samples at this rate): narrower than one frame, so a
        // frame count cannot see it. The sample count can: every sample the script holds (2660)
        // must arrive, so the test sums the sizes of the frames delivered through onFrame.
        val script = speech(2660)
        val source = FakeMicSource(script = script, holdsOpenWhenScriptSpent = true)
        source.takenAfterReads = 3
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        recorder.awaitEnd("a take the platform takes")
        val delivered = recorder.totalSamples
        assertEquals(
            "audio: drainTail output must reach the listener; got $delivered samples, " +
                "expected the whole ${script.size}-sample script",
            script.size,
            delivered,
        )
    }

    @Test
    fun `the indicator goes dark when the device is released`() {
        val source = FakeMicSource(script = speech(3200), holdsOpenWhenScriptSpent = true)
        source.takenAfterReads = 2
        val indicator = RecordingIndicator()
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, indicator = indicator, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        recorder.awaitEnd("a take the platform takes")
        assertFalse(
            "audio: the indicator must go dark when the device is released",
            indicator.isRecording,
        )
    }

    @Test
    fun `an ordinary device failure still reports DEVICE_FAILED`() {
        val source = FakeMicSource(script = speech(3200), holdsOpenWhenScriptSpent = true)
        source.failAfterReads = 2
        source.readErrorCode = -3
        val indicator = RecordingIndicator()
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, indicator = indicator, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        recorder.awaitEnd("a device error")

        val reported = recorder.endFailures[0]
        assertTrue(
            "audio: the end call got $reported, not the device error",
            reported is MicSourceException,
        )
        assertEquals(
            "audio: an ordinary device failure must report DEVICE_FAILED",
            MicSourceException.Reason.DEVICE_FAILED,
            (reported as MicSourceException).reason,
        )
        assertTrue(
            "audio: an ordinary self-end must leave the indicator lit until stop()",
            indicator.isRecording,
        )
        assertEquals(
            "audio: an ordinary self-end must leave the device open until stop()",
            0,
            source.closeCalls,
        )

        capture.stop()
        assertFalse("audio: stop() did not darken the indicator", indicator.isRecording)
        assertEquals("audio: stop() did not close the device", 1, source.closeCalls)
    }
}
