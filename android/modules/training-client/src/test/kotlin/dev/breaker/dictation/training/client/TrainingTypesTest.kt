package dev.breaker.dictation.training.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small types of the training exchange: the job id, the status, the model reply, the limits. */
class TrainingTypesTest {

    private fun jobIdRefused(value: String): Boolean {
        var refused = false
        try {
            JobId(value).value
        } catch (expected: IllegalArgumentException) {
            refused = true
        }
        return refused
    }

    @Test
    fun `a blank job id is refused`() {
        assertTrue(jobIdRefused(""))
        assertTrue(jobIdRefused("   "))
    }

    @Test
    fun `a real job id keeps its value and equals by value`() {
        assertFalse(jobIdRefused("job-1"))
        assertEquals("job-1", JobId("job-1").value)
        assertEquals(JobId("job-1"), JobId("job-1"))
        assertNotEquals(JobId("job-1"), JobId("job-2"))
    }

    @Test
    fun `job statuses are distinct and a failed status carries its code`() {
        val statuses: List<JobStatus> = listOf(
            JobStatus.Queued,
            JobStatus.Running,
            JobStatus.Done,
            JobStatus.Failed(JobFailure.TOO_FEW_SAMPLES),
            JobStatus.Failed(JobFailure.TRAINING_ERROR),
        )
        assertEquals(5, statuses.toSet().size)
        assertEquals(JobFailure.TOO_FEW_SAMPLES, JobStatus.Failed(JobFailure.TOO_FEW_SAMPLES).code)
        assertEquals(JobStatus.Failed(JobFailure.TRAINING_ERROR), JobStatus.Failed(JobFailure.TRAINING_ERROR))
    }

    @Test
    fun `a model reply hides its bytes in its text`() {
        val reply = ModelReply("abcdef0123456789", 5L, 12, byteArrayOf(111, 112, 113, 114, 115))
        val text = reply.toString()
        assertEquals("ModelReply(5 bytes)", text)
        assertFalse(text.contains("111"))
        assertFalse(text.contains("112"))
        assertFalse(text.contains("115"))
        assertFalse(text.contains("abcdef"))
    }

    @Test
    fun `a model reply keeps the values it was given`() {
        val bytes = byteArrayOf(1, 2, 3)
        val reply = ModelReply("0a1b", 3L, 15, bytes)
        assertEquals("0a1b", reply.sha256)
        assertEquals(3L, reply.sizeBytes)
        assertEquals(15, reply.samplesUsed)
        assertArrayEquals(byteArrayOf(1, 2, 3), reply.bytes)
    }

    @Test
    fun `two model replies are equal only when they are the same object`() {
        val bytes = byteArrayOf(1, 2, 3)
        val first = ModelReply("0a1b", 3L, 15, bytes)
        val second = ModelReply("0a1b", 3L, 15, bytes)
        assertEquals(first, first)
        assertNotEquals(first, second)
    }

    @Test
    fun `the upload limit holds a three second sample and stays under one MiB`() {
        assertTrue(TrainingLimits.MAX_UPLOAD_BYTES >= 96_044)
        assertTrue(TrainingLimits.MAX_UPLOAD_BYTES < 1_048_576)
    }

    @Test
    fun `the model limit is 32 MiB and is at least 20 MB`() {
        assertEquals(32L * 1024L * 1024L, TrainingLimits.MAX_MODEL_BYTES)
        assertTrue(TrainingLimits.MAX_MODEL_BYTES >= 20_000_000L)
    }
}
