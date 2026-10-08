package dev.breaker.dictation.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A take with no end callback, watched from the dispatch thread's side.
 *
 * The dispatch thread is created inside the capture, so a test cannot give it
 * a handler of its own. The JVM's default handler is set instead for the
 * length of the test and put back afterwards; it sees an uncaught throw from
 * any thread, and only those from dispatch threads are kept.
 *
 * A failure means a take with no end callback delivered fewer frames than the
 * script holds, left something running, recorded a failure, or let its
 * dispatch thread die with an uncaught exception after the take was done.
 */
class MicCaptureNoCallbackThreadTest {

    @Test
    fun `a take with no end callback ends normally and its dispatch thread dies of nothing`() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (thread.name == MicCapture.DISPATCH_THREAD_NAME) uncaught.add(error)
        }
        try {
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
            awaitNoSessionThreads("stop() with no end callback")

            assertEquals(
                "audio: all three frames of the script, the last one included, must arrive",
                3,
                frames.events.count { it == TakeEndRecorder.FRAME_EVENT },
            )
            assertFalse("audio: isCapturing is still true after stop()", capture.isCapturing)
            assertNull("audio: a take with no end callback recorded a failure", capture.failure)
            assertEquals("audio: the device was not closed exactly once", 1, source.closeCalls)
            assertFalse("audio: the indicator is still lit after stop()", indicator.isRecording)
            assertEquals(
                "audio: the dispatch thread died of an uncaught exception: $uncaught",
                emptyList<Throwable>(),
                uncaught.toList(),
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
