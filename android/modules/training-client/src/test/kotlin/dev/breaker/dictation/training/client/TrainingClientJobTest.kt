package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Polling and fetching. Not compiled or run yet. */
class TrainingClientJobTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture() = TrainingFixture(folder.root, 0, 0).also { it.saveJob("job-1") }

    private fun refusalOf(reply: ModelReply): FetchResult {
        val fx = fixture()
        fx.server.scriptDownload(TrainingResult.Ok(reply))
        return fx.client.fetchModel()
    }

    @Test
    fun `polling with no saved job answers no job and calls nothing`() {
        val fx = TrainingFixture(folder.root, 0, 0)
        assertEquals(PollResult.NoJob, fx.client.pollJob())
        assertEquals(emptyList<String>(), fx.server.calls)
    }

    @Test
    fun `a queued job is in progress and not running`() {
        val fx = fixture()
        fx.server.scriptJobStatus(TrainingResult.Ok(JobStatus.Queued))
        assertEquals(PollResult.InProgress(false), fx.client.pollJob())
        assertEquals(listOf("jobStatus job-1"), fx.server.calls)
    }

    @Test
    fun `a running job is in progress and running`() {
        val fx = fixture()
        fx.server.scriptJobStatus(TrainingResult.Ok(JobStatus.Running))
        assertEquals(PollResult.InProgress(true), fx.client.pollJob())
    }

    @Test
    fun `a done job is ready`() {
        val fx = fixture()
        fx.server.scriptJobStatus(TrainingResult.Ok(JobStatus.Done))
        assertEquals(PollResult.Ready, fx.client.pollJob())
    }

    @Test
    fun `a failed job carries its own code`() {
        val fx = fixture()
        fx.server.scriptJobStatus(
            TrainingResult.Ok(JobStatus.Failed(JobFailure.TOO_FEW_SAMPLES)),
            TrainingResult.Ok(JobStatus.Failed(JobFailure.TRAINING_ERROR)),
        )
        assertEquals(PollResult.JobFailed(JobFailure.TOO_FEW_SAMPLES), fx.client.pollJob())
        assertEquals(PollResult.JobFailed(JobFailure.TRAINING_ERROR), fx.client.pollJob())
    }

    @Test
    fun `a failed status call is a typed failure`() {
        val fx = fixture()
        fx.server.scriptJobStatus(TrainingResult.NotSignedIn, TrainingResult.Network)
        assertEquals(PollResult.Failed(TrainingFailure.NOT_SIGNED_IN), fx.client.pollJob())
        assertEquals(PollResult.Failed(TrainingFailure.NETWORK), fx.client.pollJob())
    }

    @Test
    fun `a client over a new store on the same folder still polls the job a start saved`() {
        val fx = TrainingFixture(folder.root, 10, 10)
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-9")))
        assertEquals(StartResult.Started(20), fx.client.startTraining())
        fx.server.scriptJobStatus(TrainingResult.Ok(JobStatus.Done))
        assertEquals(PollResult.Ready, fx.restarted().pollJob())
        assertEquals("jobStatus job-9", fx.server.calls.last())
    }

    @Test
    fun `a blank saved job text is no job and not a crash`() {
        val fx = TrainingFixture(folder.root, 0, 0)
        File(folder.root, "ledger.txt").writeText("job    \n", Charsets.UTF_8)
        assertEquals(PollResult.NoJob, fx.client.pollJob())
        assertEquals(FetchResult.NoJob, fx.client.fetchModel())
        assertEquals(emptyList<String>(), fx.server.calls)
    }

    @Test
    fun `fetching with no saved job answers no job and calls nothing`() {
        val fx = TrainingFixture(folder.root, 0, 0)
        assertEquals(FetchResult.NoJob, fx.client.fetchModel())
        assertEquals(emptyList<String>(), fx.server.calls)
    }

    @Test
    fun `a fetch asks the server exactly once and with the wake kind`() {
        val fx = fixture()
        fx.server.putModel(goodReply())
        assertTrue(fx.client.fetchModel() is FetchResult.Ready)
        assertEquals(listOf("downloadModel job-1 WAKE"), fx.server.calls)
    }

    @Test
    fun `a verified model carries the wake kind the bytes the lower case digest and the sample count`() {
        val fx = fixture()
        val bytes = ByteArray(40) { (it * 3).toByte() }
        val reply = ModelReply(referenceSha256(bytes).uppercase(), 40L, 14, bytes)
        fx.server.putModel(reply)
        val ready = fx.client.fetchModel() as FetchResult.Ready
        assertEquals(PhraseKind.WAKE, ready.model.kind)
        assertEquals(PhraseKind.WAKE, ready.model.model.kind)
        assertTrue(bytes.contentEquals(ready.model.model.bytes))
        assertEquals(referenceSha256(bytes), ready.model.model.sha256)
        assertEquals(14, ready.model.samplesUsed)
    }

    @Test
    fun `a failed download is a typed failure`() {
        val fx = fixture()
        fx.server.scriptDownload(TrainingResult.TooLarge, TrainingResult.Network)
        assertEquals(FetchResult.Failed(TrainingFailure.TOO_LARGE), fx.client.fetchModel())
        assertEquals(FetchResult.Failed(TrainingFailure.NETWORK), fx.client.fetchModel())
    }

    @Test
    fun `an out of range size is refused and nothing is returned`() {
        val reply = ModelReply(referenceSha256(ByteArray(8)), 0L, 5, ByteArray(0))
        assertEquals(FetchResult.Refused(FetchRefusal.SIZE_OUT_OF_RANGE), refusalOf(reply))
    }

    @Test
    fun `a size mismatch is refused and nothing is returned`() {
        val bytes = ByteArray(8) { 1 }
        val reply = ModelReply(referenceSha256(bytes), 9L, 5, bytes)
        assertEquals(FetchResult.Refused(FetchRefusal.SIZE_MISMATCH), refusalOf(reply))
    }

    @Test
    fun `a malformed digest is refused and nothing is returned`() {
        val bytes = ByteArray(8) { 1 }
        val reply = ModelReply("zz", 8L, 5, bytes)
        assertEquals(FetchResult.Refused(FetchRefusal.BAD_DIGEST_FORMAT), refusalOf(reply))
    }

    @Test
    fun `a tampered model is refused and nothing is returned`() {
        val bytes = ByteArray(8) { 1 }
        val tampered = ByteArray(8) { 2 }
        val reply = ModelReply(referenceSha256(bytes), 8L, 5, tampered)
        assertEquals(FetchResult.Refused(FetchRefusal.DIGEST_MISMATCH), refusalOf(reply))
    }

    @Test
    fun `a model with zero samples used is refused and not thrown`() {
        val bytes = ByteArray(8) { 1 }
        val reply = ModelReply(referenceSha256(bytes), 8L, 0, bytes)
        assertEquals(FetchResult.Refused(FetchRefusal.BAD_SAMPLES_COUNT), refusalOf(reply))
    }
}
