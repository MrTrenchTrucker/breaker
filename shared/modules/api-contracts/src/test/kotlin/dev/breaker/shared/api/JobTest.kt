package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JobTest {

    private val doneResult = TranscriptionResult(
        text = "Hello",
        segments = listOf(Segment(0.0, 1.0, "Hello")),
        language = "en"
    )

    @Test
    fun `Job with status DONE must have non-null result`() {
        val job = Job(status = JobStatus.DONE, result = doneResult)
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
    fun `Job with status FAILED requires a non-null error`() {
        val job = Job(status = JobStatus.FAILED, result = null, error = "whisper exploded")
        assertEquals("whisper exploded", job.error)
    }

    @Test
    fun `Job with status FAILED and null error throws`() {
        try {
            Job(status = JobStatus.FAILED, result = null, error = null)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the error invariant, was: ${e.message}",
                e.message!!.contains("error")
            )
        }
    }

    @Test
    fun `Job with a non-FAILED status and an error throws`() {
        try {
            Job(status = JobStatus.DONE, result = doneResult, error = "boom")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the error invariant, was: ${e.message}",
                e.message!!.contains("error")
            )
        }
    }
}
