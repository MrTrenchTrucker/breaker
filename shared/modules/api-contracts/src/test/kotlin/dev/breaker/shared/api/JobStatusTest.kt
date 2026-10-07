package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobStatusTest {

    @Test
    fun `JobStatus has exactly four values`() {
        val values = JobStatus.values()
        assertEquals("JobStatus must have exactly 4 values", 4, values.size)
    }

    @Test
    fun `JobStatus values are QUEUED, PROCESSING, DONE, FAILED`() {
        val names = JobStatus.values().map { it.name }
        assertEquals(listOf("QUEUED", "PROCESSING", "DONE", "FAILED"), names)
    }

    @Test
    fun `fromString returns correct value for each status`() {
        assertEquals(JobStatus.QUEUED, JobStatus.fromString("queued"))
        assertEquals(JobStatus.PROCESSING, JobStatus.fromString("processing"))
        assertEquals(JobStatus.DONE, JobStatus.fromString("done"))
        assertEquals(JobStatus.FAILED, JobStatus.fromString("failed"))
    }

    @Test
    fun `fromString is case-insensitive`() {
        assertEquals(JobStatus.QUEUED, JobStatus.fromString("QUEUED"))
        assertEquals(JobStatus.PROCESSING, JobStatus.fromString("Processing"))
        assertEquals(JobStatus.DONE, JobStatus.fromString("Done"))
        assertEquals(JobStatus.FAILED, JobStatus.fromString("FAILED"))
    }

    @Test
    fun `fromString returns null for unknown value`() {
        assertNull(JobStatus.fromString("unknown"))
        assertNull(JobStatus.fromString(""))
    }
}
