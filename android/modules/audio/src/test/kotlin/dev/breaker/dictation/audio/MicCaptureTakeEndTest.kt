package dev.breaker.dictation.audio

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-of-take callback on the endings a take can have: a caller stop, a
 * capture that ends by itself, a take whose length is a whole number of frames,
 * and two takes one after the other.
 *
 * A failure means the caller is not told, or is told twice, or is told before
 * the take's last frame, and a screen waiting on it is stuck on "recording".
 */
class MicCaptureTakeEndTest {

    @Test
    fun `a caller stop ends the take once after its last frame with no failure`() {
        val source = FakeMicSource(script = speech(960), holdsOpenWhenScriptSpent = true)
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        assertTrue(
            "audio: the script was never spent, so the test is not in the state it is about",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        capture.stop()

        assertEquals("audio: the end call must have run once by the time stop() returns", 1, recorder.endCount)
        assertNull("audio: a clean caller stop was reported as a failure", recorder.endFailures[0])
        assertEquals(
            "audio: every frame must come before the end call, and nothing after it",
            listOf("frame", "END"),
            recorder.runs(),
        )
        capture.stop()
        assertEquals("audio: a second stop() told the caller again", 1, recorder.endCount)
    }

    @Test
    fun `a capture that ends by itself reports its failure on its own and stop is still owed`() {
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 200))
        source.failAfterReads = 2
        source.readErrorCode = -3
        val indicator = RecordingIndicator()
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, indicator = indicator, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        // stop() is deliberately not called: the end must arrive by itself.
        recorder.awaitEnd("a device error")

        val reported = recorder.endFailures[0]
        assertTrue("audio: the end call got $reported, not the device error", reported is MicSourceException)
        assertTrue(
            "audio: the failure should name the driver error code, was: ${reported!!.message}",
            reported.message!!.contains("-3"),
        )
        assertEquals(
            "audio: a device failure must default to DEVICE_FAILED",
            MicSourceException.Reason.DEVICE_FAILED,
            (reported as MicSourceException).reason,
        )
        assertSame("audio: the end call and MicCapture.failure disagree", capture.failure, reported)
        assertTrue("audio: a self-end must leave the indicator lit until stop()", indicator.isRecording)
        assertEquals("audio: a self-end must leave the device open until stop()", 0, source.closeCalls)

        capture.stop()
        assertEquals("audio: stop() after a self-end told the caller a second time", 1, recorder.endCount)
        assertEquals(
            "audio: every frame must come before the end call, and nothing after it",
            listOf("frame", "END"),
            recorder.runs(),
        )
        assertFalse("audio: stop() did not darken the indicator", indicator.isRecording)
        assertEquals("audio: stop() did not close the device", 1, source.closeCalls)
    }

    @Test
    fun `a take of an exact number of frames still ends with a full last frame and then the end`() {
        // A whole number of frames has no short last frame to show the take is
        // over, so the end call is the only thing that says so.
        val source = FakeMicSource(script = speech(320), holdsOpenWhenScriptSpent = true)
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        assertTrue(
            "audio: the script was never spent, so the test is not in the state it is about",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        capture.stop()

        assertEquals(
            "audio: one whole frame, then the end call",
            listOf("frame", "END"),
            recorder.events.toList(),
        )
    }

    @Test
    fun `each take of a restarted capture ends once with its own failure`() {
        val source = FakeMicSource(script = speech(3_200), holdsOpenWhenScriptSpent = true)
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, onTakeEnded = recorder.onTakeEnded)

        capture.start(recorder.frames("take1"))
        assertTrue(
            "audio: the first take never spent its script",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        capture.stop()
        recorder.awaitEnd("the first take")
        assertEquals("audio: the first take must have ended once", 1, recorder.endCount)

        // The second take's device fails two reads after it starts.
        source.failAfterReads = source.readCalls + 2
        source.readErrorCode = -3
        capture.start(recorder.frames("take2"))
        recorder.awaitEnd("the second take")
        capture.stop()

        assertEquals("audio: two takes must end exactly twice", 2, recorder.endCount)
        assertEquals(
            "audio: each take's frames must come before its own end call, and none after",
            listOf("take1", "END", "take2", "END"),
            recorder.runs(),
        )
        assertNull("audio: the clean first take was reported as a failure", recorder.endFailures[0])
        val second = recorder.endFailures[1]
        assertTrue("audio: the second take's end call got $second", second is MicSourceException)
        assertTrue(
            "audio: the second failure should name the driver error code, was: ${second!!.message}",
            second.message!!.contains("-3"),
        )
        assertNotSame("audio: the second take was handed the first take's record", recorder.endFailures[0], second)
    }
}
