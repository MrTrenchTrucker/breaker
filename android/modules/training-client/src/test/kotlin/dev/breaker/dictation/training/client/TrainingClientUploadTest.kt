package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Failures, limits, pending deletes and job start in startTraining. Not compiled or run yet. */
class TrainingClientUploadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture(wake: Int = 10, send: Int = 10) = TrainingFixture(folder.root, wake, send)

    private fun uploads(fx: TrainingFixture): List<String> = fx.server.calls.filter { it.startsWith("uploadSample") }

    @Test
    fun `a lost reply is retried with the same id and the server keeps one sample`() {
        val fx = fixture()
        fx.server.loseReplyOnUpload = true
        assertEquals(StartResult.UploadFailed(TrainingFailure.NETWORK), fx.client.startTraining())
        val firstCall = uploads(fx).single()
        val firstId = firstCall.split(' ')[2]
        assertEquals(1, fx.server.uploadedIds.size)
        assertEquals(emptySet<String>(), fx.store.confirmed(PhraseKind.WAKE))
        fx.server.loseReplyOnUpload = false
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-1")))
        assertEquals(StartResult.Started(20), fx.client.startTraining())
        assertEquals(2, uploads(fx).count { it == firstCall })
        assertEquals(1, fx.server.storedSamples.count { it.sampleId == firstId })
        assertEquals(20, fx.server.uploadedIds.size)
    }

    @Test
    fun `signed out gives not offered and the local samples are untouched`() {
        val fx = fixture()
        fx.server.scriptUpload(TrainingResult.NotSignedIn)
        assertEquals(StartResult.NotOffered(TrainingFailure.NOT_SIGNED_IN), fx.client.startTraining())
        assertEquals(10, fx.local(PhraseKind.WAKE))
        assertEquals(10, fx.local(PhraseKind.SEND))
        assertEquals(emptySet<String>(), fx.store.confirmed(PhraseKind.WAKE))
        assertEquals(1, uploads(fx).size)
    }

    @Test
    fun `no server gives not offered and the local samples are untouched`() {
        val fx = fixture()
        fx.server.scriptUpload(TrainingResult.NoServer)
        assertEquals(StartResult.NotOffered(TrainingFailure.NO_SERVER), fx.client.startTraining())
        assertEquals(10, fx.local(PhraseKind.WAKE))
        assertEquals(10, fx.local(PhraseKind.SEND))
    }

    @Test
    fun `a job start while signed out gives not offered and the samples stay confirmed`() {
        val fx = fixture()
        fx.server.scriptStartJob(TrainingResult.NotSignedIn)
        assertEquals(StartResult.NotOffered(TrainingFailure.NOT_SIGNED_IN), fx.client.startTraining())
        assertEquals(10, fx.store.confirmed(PhraseKind.WAKE).size)
        assertEquals(10, fx.store.confirmed(PhraseKind.SEND).size)
    }

    @Test
    fun `a refusal a too large answer and an unavailable server are upload failures`() {
        val fx = fixture()
        fx.server.scriptUpload(TrainingResult.Refused, TrainingResult.TooLarge, TrainingResult.Unavailable)
        assertEquals(StartResult.UploadFailed(TrainingFailure.REFUSED), fx.client.startTraining())
        assertEquals(StartResult.UploadFailed(TrainingFailure.TOO_LARGE), fx.client.startTraining())
        assertEquals(StartResult.UploadFailed(TrainingFailure.UNAVAILABLE), fx.client.startTraining())
        assertEquals(10, fx.local(PhraseKind.WAKE))
    }

    @Test
    fun `a sample over the upload limit is refused locally with no call for it`() {
        val fx = fixture(0, 0)
        repeat(9) { fx.put(PhraseKind.WAKE) }
        val big = fx.put(PhraseKind.WAKE, 131_066)
        repeat(10) { fx.put(PhraseKind.SEND) }
        assertEquals(StartResult.SampleTooLarge(PhraseKind.WAKE, big), fx.client.startTraining())
        assertTrue(uploads(fx).none { it.startsWith("uploadSample WAKE $big ") })
        assertTrue(fx.store.list(PhraseKind.WAKE).contains(big))
        assertFalse(fx.server.calls.contains("startJob"))
    }

    @Test
    fun `a sample exactly at the upload limit is sent`() {
        val fx = fixture(0, 0)
        repeat(9) { fx.put(PhraseKind.WAKE) }
        fx.put(PhraseKind.WAKE, 131_065)
        repeat(10) { fx.put(PhraseKind.SEND) }
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-1")))
        assertEquals(StartResult.Started(20), fx.client.startTraining())
        assertTrue(uploads(fx).any { it.endsWith(" 131072") })
    }

    @Test
    fun `a pending server delete is retried before anything is uploaded and then cleared`() {
        val fx = fixture()
        assertTrue(fx.store.setServerDeletePending(true) is StoreResult.Ok)
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-1")))
        assertEquals(StartResult.Started(20), fx.client.startTraining())
        assertEquals(listOf("deleteMySamples", "deleteMyModel"), fx.server.calls.take(2))
        assertTrue(fx.server.calls[2].startsWith("uploadSample"))
        assertFalse(fx.store.serverDeletePending())
    }

    @Test
    fun `a pending server delete that fails uploads nothing and keeps the mark`() {
        val fx = fixture()
        assertTrue(fx.store.setServerDeletePending(true) is StoreResult.Ok)
        fx.server.scriptDeleteSamples(TrainingResult.Network)
        assertEquals(StartResult.ServerDeletePending(TrainingFailure.NETWORK), fx.client.startTraining())
        assertEquals(listOf("deleteMySamples", "deleteMyModel"), fx.server.calls)
        assertTrue(fx.store.serverDeletePending())
        assertEquals(10, fx.local(PhraseKind.WAKE))
    }

    @Test
    fun `a pending server delete whose model delete fails reports that failure`() {
        val fx = fixture()
        assertTrue(fx.store.setServerDeletePending(true) is StoreResult.Ok)
        fx.server.scriptDeleteModel(TrainingResult.Unavailable)
        assertEquals(StartResult.ServerDeletePending(TrainingFailure.UNAVAILABLE), fx.client.startTraining())
        assertTrue(fx.store.serverDeletePending())
        assertEquals(2, fx.server.calls.size)
    }

    @Test
    fun `a failed job start keeps the confirmed samples and a retry only starts the job`() {
        val fx = fixture()
        fx.server.scriptStartJob(TrainingResult.Unavailable)
        assertEquals(StartResult.JobNotStarted(TrainingFailure.UNAVAILABLE), fx.client.startTraining())
        assertEquals(0, fx.local(PhraseKind.WAKE))
        assertEquals(10, fx.store.confirmed(PhraseKind.WAKE).size)
        assertEquals(null, fx.store.loadJob())
        val before = fx.server.calls.size
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-3")))
        assertEquals(StartResult.Started(0), fx.client.startTraining())
        assertEquals(listOf("startJob"), fx.server.calls.drop(before))
        assertEquals("job-3", fx.store.loadJob())
    }
}
