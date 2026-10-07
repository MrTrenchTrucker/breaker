package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.TestDatabases
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SeamTypesTest {

    @Test
    fun `an empty success result is refused`() {
        TestDatabases.expectFailure<IllegalArgumentException>("whisper-server: an empty result must be refused") {
            ForwardOutcome.Success("")
        }
    }

    @Test
    fun `a blank success result is refused`() {
        TestDatabases.expectFailure<IllegalArgumentException>("whisper-server: a blank result must be refused") {
            ForwardOutcome.Success("   ")
        }
    }

    @Test
    fun `a success with text keeps its result`() {
        assertEquals("whisper-server: the result must be kept as given", "x", ForwardOutcome.Success("x").result)
    }

    @Test
    fun `a success does not show its text when converted to a string`() {
        val text = ForwardOutcome.Success("private-transcript-text").toString()

        assertEquals("whisper-server: the redacted form is fixed", "ForwardOutcome.Success(redacted)", text)
        assertFalse("whisper-server: the result text leaked", text.contains("private-transcript-text"))
    }

    @Test
    fun `a request shows its job and attempt but never the audio`() {
        val audio = byteArrayOf(11, 22, 33, 44)

        val text = ForwardRequest(7L, audio, 3).toString()

        assertEquals("whisper-server: the request text is fixed", "ForwardRequest(jobId=7, attempt=3)", text)
        assertTrue("whisper-server: the job id must be shown", text.contains("jobId=7"))
        assertTrue("whisper-server: the attempt must be shown", text.contains("attempt=3"))
        assertFalse("whisper-server: the audio array leaked", text.contains("[B@"))
    }

    @Test
    fun `the real pause returns for a zero duration`(): Unit = WorkerFixtures.runBounded {
        val startedNanos = System.nanoTime()

        Pause.REAL.pause(Duration.ZERO)

        val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
        assertTrue("whisper-server: a zero pause must return at once but took $elapsedMillis ms", elapsedMillis < 10_000L)
    }
}
