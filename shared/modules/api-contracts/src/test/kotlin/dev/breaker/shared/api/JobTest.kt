package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JobTest {

    @Test
    fun `Job with status DONE must have non-null result`() {
        val result = TranscriptionResult(
            text = "Hello",
            segments = listOf(Segment(0.0, 1.0, "Hello")),
            language = "en"
        )
        val job = Job(status = JobStatus.DONE, result = result)
        assertNotNull(job.result)
        assertEquals("Hello", job.result?.text)
    }

    @Test
    fun `Job with status DONE and null result throws`() {
        try {
            Job(status = JobStatus.DONE, result = null)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the result invariant, was: ${e.message}",
                e.message!!.contains("result")
            )
        }
    }

    @Test
    fun `Job with status QUEUED can have null result`() {
        val job = Job(status = JobStatus.QUEUED, result = null)
        assertNull(job.result)
    }

    @Test
    fun `Job with status PROCESSING can have null result`() {
        val job = Job(status = JobStatus.PROCESSING, result = null)
        assertNull(job.result)
    }

    @Test
    fun `Job with status FAILED can have null result`() {
        val job = Job(status = JobStatus.FAILED, result = null)
        assertNull(job.result)
    }
}
