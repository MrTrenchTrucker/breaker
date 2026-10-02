package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JobAcceptedTest {

    @Test
    fun `JobAccepted status defaults to QUEUED`() {
        val accepted = JobAccepted(jobId = "job-12345")
        assertEquals(JobStatus.QUEUED, accepted.status)
    }

    @Test
    fun `JobAccepted with an explicit QUEUED status is accepted`() {
        val accepted = JobAccepted(jobId = "job-12345", status = JobStatus.QUEUED)
        assertEquals("job-12345", accepted.jobId)
        assertEquals(JobStatus.QUEUED, accepted.status)
    }

    @Test
    fun `JobAccepted with a non-QUEUED status throws`() {
        // The spec narrows the 202 status to [queued] (JobStatus $ref +
        // sibling enum); the Kotlin side keeps the same narrowing at
        // construction.
        try {
            JobAccepted(jobId = "job-12345", status = JobStatus.DONE)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should name the queued invariant, was: ${e.message}",
                e.message!!.contains("queued")
            )
        }
    }
}
