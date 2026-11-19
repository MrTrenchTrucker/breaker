package dev.breaker.shared.api

import dev.breaker.shared.api.training.TrainingContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The training types of the shared contract: the limits and header names equal the literals of
 * the spec, each enum carries exactly the wire strings of its schema in order, and the three
 * data classes keep the field shapes of their schemas.
 */
class TrainingContractTest {

    private fun wire(names: List<String>): List<String> = names.map { it.lowercase() }

    private fun iae(block: () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e
        }
        throw AssertionError("api-contracts test: expected IllegalArgumentException but the constructor returned")
    }

    // ---------- limits and header names ----------

    @Test
    fun `the sample upload cap is 131072 bytes`() {
        assertEquals(131072, TrainingContract.MAX_SAMPLE_BYTES)
    }

    @Test
    fun `the model cap is 32 MiB`() {
        assertEquals(33554432, TrainingContract.MAX_MODEL_BYTES)
        assertEquals(32 * 1024 * 1024, TrainingContract.MAX_MODEL_BYTES)
    }

    @Test
    fun `a kind needs 10 to 20 samples`() {
        assertEquals(10, TrainingContract.MIN_SAMPLES_PER_KIND)
        assertEquals(20, TrainingContract.MAX_SAMPLES_PER_KIND)
        assertTrue(TrainingContract.MIN_SAMPLES_PER_KIND < TrainingContract.MAX_SAMPLES_PER_KIND)
    }

    @Test
    fun `the three model header names are the spec names`() {
        assertEquals("X-Model-Sha256", TrainingContract.HEADER_MODEL_SHA256)
        assertEquals("X-Model-Size-Bytes", TrainingContract.HEADER_MODEL_SIZE_BYTES)
        assertEquals("X-Model-Samples-Used", TrainingContract.HEADER_MODEL_SAMPLES_USED)
    }

    // ---------- enum wire values ----------

    @Test
    fun `the training kinds are wake and send in that order`() {
        assertEquals(listOf("wake", "send"), wire(TrainingKind.values().map { it.name }))
    }

    @Test
    fun `the training job states are queued running done failed in that order`() {
        assertEquals(
            listOf("queued", "running", "done", "failed"),
            wire(TrainingJobState.values().map { it.name }),
        )
    }

    @Test
    fun `the training job failures are too_few_samples and training_error in that order`() {
        assertEquals(
            listOf("too_few_samples", "training_error"),
            wire(TrainingJobFailure.values().map { it.name }),
        )
    }

    @Test
    fun `fromString finds each training enum value and answers null for an unknown word`() {
        assertEquals(TrainingKind.SEND, TrainingKind.fromString("send"))
        assertEquals(TrainingJobState.RUNNING, TrainingJobState.fromString("running"))
        assertEquals(TrainingJobFailure.TOO_FEW_SAMPLES, TrainingJobFailure.fromString("too_few_samples"))
        assertNull(TrainingKind.fromString("both"))
        assertNull(TrainingJobState.fromString("processing"))
        assertNull(TrainingJobFailure.fromString("timeout"))
    }

    // ---------- data class shapes ----------

    @Test
    fun `a sample receipt carries its four fields and equals one built from the same values`() {
        val receipt = SampleReceipt(sampleId = "sample_a", kind = TrainingKind.WAKE, sizeBytes = 4096, count = 3)
        assertEquals("sample_a", receipt.sampleId)
        assertEquals(TrainingKind.WAKE, receipt.kind)
        assertEquals(4096, receipt.sizeBytes)
        assertEquals(3, receipt.count)
        assertEquals(receipt, SampleReceipt("sample_a", TrainingKind.WAKE, 4096, 3))
        assertNotEquals(receipt, SampleReceipt("sample_a", TrainingKind.SEND, 4096, 3))
        assertNotEquals(receipt, SampleReceipt("sample_a", TrainingKind.WAKE, 4096, 4))
    }

    @Test
    fun `a training job carries its id and its state`() {
        val job = TrainingJob(jobId = "j-1", state = TrainingJobState.QUEUED)
        assertEquals("j-1", job.jobId)
        assertEquals(TrainingJobState.QUEUED, job.state)
        assertEquals(job, TrainingJob("j-1", TrainingJobState.QUEUED))
        assertNotEquals(job, TrainingJob("j-1", TrainingJobState.RUNNING))
    }

    @Test
    fun `a status of a job that is not failed has no failure by default`() {
        for (state in listOf(TrainingJobState.QUEUED, TrainingJobState.RUNNING, TrainingJobState.DONE)) {
            val status = TrainingJobStatus(jobId = "j-1", state = state)
            assertNull("api-contracts: failure is optional and defaults to none for $state", status.failure)
        }
    }

    @Test
    fun `a failed status carries either failure reason`() {
        val few = TrainingJobStatus("j-1", TrainingJobState.FAILED, TrainingJobFailure.TOO_FEW_SAMPLES)
        val error = TrainingJobStatus("j-1", TrainingJobState.FAILED, TrainingJobFailure.TRAINING_ERROR)
        assertEquals(TrainingJobFailure.TOO_FEW_SAMPLES, few.failure)
        assertEquals(TrainingJobFailure.TRAINING_ERROR, error.failure)
        assertNotEquals(few, error)
    }

    @Test
    fun `a failed status without a failure reason is refused`() {
        val e = iae { TrainingJobStatus("j-1", TrainingJobState.FAILED) }
        assertTrue("message names the failure: ${e.message}", e.message!!.contains("failure"))
    }

    @Test
    fun `a status that is not failed refuses a failure reason`() {
        for (state in listOf(TrainingJobState.QUEUED, TrainingJobState.RUNNING, TrainingJobState.DONE)) {
            val e = iae { TrainingJobStatus("j-1", state, TrainingJobFailure.TRAINING_ERROR) }
            assertTrue("message names the failure: ${e.message}", e.message!!.contains("failure"))
        }
    }
}
