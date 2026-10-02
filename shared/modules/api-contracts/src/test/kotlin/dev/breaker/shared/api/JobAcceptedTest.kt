package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Test

class JobAcceptedTest {

    @Test
    fun `JobAccepted has jobId and status fields`() {
        val accepted = JobAccepted(
            jobId = "job-12345",
            status = "queued"
        )
        assertEquals("job-12345", accepted.jobId)
        assertEquals("queued", accepted.status)
    }

    @Test
    fun `JobAccepted status defaults to queued`() {
        val accepted = JobAccepted(jobId = "job-12345")
        assertEquals("queued", accepted.status)
    }
}
