package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What stays true around the end-of-take callback: which thread runs it, a
 * replaced take stays silent, a caller that throws from it cannot break the
 * teardown, and leaving it unset changes nothing.
 *
 * A failure means a misbehaving or absent callback can change how a take is
 * stopped, who is told, or what [MicCapture.failure] says.
 */
class MicCaptureTakeEndContainmentTest {

    @Test
    fun `the end call runs on the dispatch thread and not on the caller's`() {
        val source = FakeMicSource(script = speech(960), holdsOpenWhenScriptSpent = true)
        val recorder = TakeEndRecorder()
        val capture = MicCapture(source = source, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        assertTrue(
            "audio: the script was never spent, so the test is not in the state it is about",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        capture.stop()

        assertEquals(
            "audio: the end call must run on the dispatch thread",
            listOf(MicCapture.DISPATCH_THREAD_NAME),
            recorder.endThreadNames.toList(),
        )
        assertNotEquals(
            "audio: the end call ran on the thread that called stop()",
            Thread.currentThread().name,
            recorder.endThreadNames[0],
        )
    }

    @Test
    fun `an end call that throws is recorded as the failure and the teardown still completes`() {
        val thrown = IllegalStateException("end listener exploded")
        val source = FakeMicSource(script = speech(960), holdsOpenWhenScriptSpent = true)
        val indicator = RecordingIndicator()
        val recorder = TakeEndRecorder(throwOnEnd = thrown)
        val capture = MicCapture(source = source, indicator = indicator, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        assertTrue(
            "audio: the script was never spent, so the test is not in the state it is about",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        try {
            capture.stop()
        } catch (e: Throwable) {
            fail("audio: stop() raised the end callback's exception: $e")
        }

        assertEquals("audio: the end call must have run once", 1, recorder.endCount)
        assertSame(
            "audio: a throw from the end call was not recorded in MicCapture.failure",
            thrown,
            capture.failure,
        )
        assertEquals("audio: the device was not closed exactly once", 1, source.closeCalls)
        assertFalse("audio: the indicator is still lit after stop()", indicator.isRecording)
        assertFalse("audio: isCapturing is still true after stop()", capture.isCapturing)
        awaitNoSessionThreads("stop() with an end call that throws")
    }

    @Test
    fun `an end call that throws does not hide a failure recorded before it`() {
        val source = FakeMicSource(script = silenceThenSpeech(totalMs = 200))
        source.failAfterReads = 2
        source.readErrorCode = -3
        val indicator = RecordingIndicator()
        val recorder = TakeEndRecorder(throwOnEnd = IllegalStateException("end listener exploded"))
        val capture = MicCapture(source = source, indicator = indicator, onTakeEnded = recorder.onTakeEnded)
        capture.start(recorder.frames())
        recorder.awaitEnd("a device error")
        try {
            capture.stop()
        } catch (e: Throwable) {
            fail("audio: stop() raised the end callback's exception: $e")
        }

        val failure = capture.failure
        assertNotNull("audio: no failure was recorded at all", failure)
        assertTrue(
            "audio: the device error was replaced by the callback's throw: $failure",
            failure is MicSourceException && failure.message!!.contains("-3"),
        )
        assertEquals("audio: the device was not closed exactly once", 1, source.closeCalls)
        assertFalse("audio: the indicator is still lit after stop()", indicator.isRecording)
    }

    @Test
    fun `a take replaced by a later start stays silent`() {
        val source = FakeMicSource(script = speech(960), holdsOpenWhenScriptSpent = true)
        val recorder = TakeEndRecorder()
        val capture = MicCapture(
            source = source,
            joinTimeoutMs = IndicatorTestSupport.STUCK_JOIN_TIMEOUT_MS,
            onTakeEnded = recorder.onTakeEnded,
        )
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        capture.start(AudioListener {
            parked.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
        })
        assertTrue(
            "audio: the first take's listener was never reached, so no dispatcher is parked",
            parked.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        // The dispatcher is parked, so stop() gives up on it after the join bound.
        capture.stop()
        assertEquals("audio: a parked dispatcher cannot have ended its take yet", 0, recorder.endCount)

        val secondFrame = CountDownLatch(1)
        capture.start(AudioListener {
            recorder.events.add("second")
            secondFrame.countDown()
        })
        // The first take's dispatcher wakes now, belonging to a replaced take.
        release.countDown()
        assertTrue(
            "audio: the second take never delivered a frame",
            secondFrame.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        capture.stop()
        awaitNoSessionThreads("the replaced take was released and the second take stopped")

        assertEquals(
            "audio: only the second take may report an end; the replaced take must stay silent",
            1,
            recorder.endCount,
        )
        assertEquals("audio: the one end call must follow the second take's frames", listOf("second", "END"), recorder.runs())
    }

    @Test
    fun `without an end callback a take behaves as it always did`() {
        val source = FakeMicSource(script = speech(960), holdsOpenWhenScriptSpent = true)
        val indicator = RecordingIndicator()
        val frames = TakeEndRecorder()
        val capture = MicCapture(source = source, indicator = indicator)
        capture.start(frames.frames())
        assertTrue(
            "audio: the script was never spent, so the test is not in the state it is about",
            source.scriptSpent.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        capture.stop()

        assertEquals("audio: frames must have been delivered and nothing else recorded", listOf("frame"), frames.runs())
        assertNull("audio: a take with no end callback recorded a failure", capture.failure)
        assertEquals("audio: the device was not closed exactly once", 1, source.closeCalls)
        assertFalse("audio: the indicator is still lit after stop()", indicator.isRecording)
        awaitNoSessionThreads("stop() with no end callback")
    }
}
