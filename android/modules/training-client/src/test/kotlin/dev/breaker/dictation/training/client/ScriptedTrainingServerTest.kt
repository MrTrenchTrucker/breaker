package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fake server behaves the way the tests that lean on it expect. */
class ScriptedTrainingServerTest {

    private val ok = TrainingResult.Ok(Unit)

    private fun reply(): ModelReply = ModelReply("0a1b", 3L, 15, byteArrayOf(1, 2, 3))

    @Test
    fun `a repeated upload of one id stores one sample and both calls are Ok`() {
        val server = ScriptedTrainingServer()
        val first = server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1, 2, 3))
        val second = server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1, 2, 3))
        assertEquals(ok, first)
        assertEquals(ok, second)
        assertEquals(1, server.storedSamples.size)
        assertEquals(setOf("a"), server.uploadedIds)
        assertEquals(2, server.calls.size)
    }

    @Test
    fun `different ids are stored as separate samples`() {
        val server = ScriptedTrainingServer()
        server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1))
        server.uploadSample(PhraseKind.SEND, "b", byteArrayOf(2))
        assertEquals(2, server.storedSamples.size)
        assertEquals(setOf("a", "b"), server.uploadedIds)
        assertEquals(PhraseKind.SEND, server.storedSamples[1].kind)
    }

    @Test
    fun `scripted upload results come back in order and then the default`() {
        val server = ScriptedTrainingServer()
        server.scriptUpload(TrainingResult.TooLarge, TrainingResult.Refused)
        assertEquals(TrainingResult.TooLarge, server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1)))
        assertEquals(TrainingResult.Refused, server.uploadSample(PhraseKind.WAKE, "b", byteArrayOf(1)))
        assertEquals(ok, server.uploadSample(PhraseKind.WAKE, "c", byteArrayOf(1)))
    }

    @Test
    fun `scripted job statuses come back in order and then the default`() {
        val server = ScriptedTrainingServer()
        val job = JobId("j1")
        server.scriptJobStatus(
            TrainingResult.Ok(JobStatus.Queued),
            TrainingResult.Ok(JobStatus.Running),
            TrainingResult.Ok(JobStatus.Done),
        )
        assertEquals(TrainingResult.Ok(JobStatus.Queued), server.jobStatus(job))
        assertEquals(TrainingResult.Ok(JobStatus.Running), server.jobStatus(job))
        assertEquals(TrainingResult.Ok(JobStatus.Done), server.jobStatus(job))
        assertEquals(TrainingResult.Unavailable, server.jobStatus(job))
    }

    @Test
    fun `a scripted upload failure stores nothing`() {
        val server = ScriptedTrainingServer()
        server.scriptUpload(TrainingResult.Refused)
        assertEquals(TrainingResult.Refused, server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1)))
        assertTrue(server.storedSamples.isEmpty())
        assertTrue(server.uploadedIds.isEmpty())
    }

    @Test
    fun `a lost reply stores the sample and still answers Network and a retry stays one sample`() {
        val server = ScriptedTrainingServer()
        server.loseReplyOnUpload = true
        assertEquals(TrainingResult.Network, server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1, 2)))
        assertEquals(setOf("a"), server.uploadedIds)
        assertEquals(1, server.storedSamples.size)

        server.loseReplyOnUpload = false
        assertEquals(ok, server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1, 2)))
        assertEquals(1, server.storedSamples.size)
    }

    @Test
    fun `the call log lists the operations in order with their arguments`() {
        val server = ScriptedTrainingServer()
        val port: TrainingServer = server
        val job = JobId("j1")
        port.uploadSample(PhraseKind.WAKE, "x", byteArrayOf(1, 2, 3))
        port.startJob()
        port.jobStatus(job)
        port.downloadModel(job, PhraseKind.SEND)
        port.deleteMySamples()
        port.deleteMyModel()
        assertEquals(
            listOf(
                "uploadSample WAKE x 3",
                "startJob",
                "jobStatus j1",
                "downloadModel j1 SEND",
                "deleteMySamples",
                "deleteMyModel",
            ),
            server.calls,
        )
    }

    @Test
    fun `the fake keeps its own copy of the uploaded bytes`() {
        val server = ScriptedTrainingServer()
        val bytes = byteArrayOf(1, 2, 3)
        server.uploadSample(PhraseKind.WAKE, "a", bytes)
        bytes[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), server.storedSamples[0].wav)
    }

    @Test
    fun `deleting my samples clears the stored samples and ids`() {
        val server = ScriptedTrainingServer()
        server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1))
        server.uploadSample(PhraseKind.SEND, "b", byteArrayOf(2))
        assertEquals(ok, server.deleteMySamples())
        assertTrue(server.storedSamples.isEmpty())
        assertTrue(server.uploadedIds.isEmpty())
    }

    @Test
    fun `a failed delete of samples leaves them stored`() {
        val server = ScriptedTrainingServer()
        server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1))
        server.scriptDeleteSamples(TrainingResult.Network)
        assertEquals(TrainingResult.Network, server.deleteMySamples())
        assertEquals(1, server.storedSamples.size)
    }

    @Test
    fun `a stored model is served and deleting my model clears it`() {
        val server = ScriptedTrainingServer()
        val model = reply()
        val job = JobId("j1")
        server.putModel(model)
        val served = server.downloadModel(job, PhraseKind.WAKE)
        assertTrue(served is TrainingResult.Ok<*>)
        assertSame(model, (served as? TrainingResult.Ok<*>)?.value)

        assertEquals(ok, server.deleteMyModel())
        assertNull(server.storedModel)
        assertEquals(TrainingResult.Unavailable, server.downloadModel(job, PhraseKind.WAKE))
    }

    @Test
    fun `a scripted download result wins over the stored model once`() {
        val server = ScriptedTrainingServer()
        val job = JobId("j1")
        server.putModel(reply())
        server.scriptDownload(TrainingResult.TooLarge)
        assertEquals(TrainingResult.TooLarge, server.downloadModel(job, PhraseKind.WAKE))
        assertTrue(server.downloadModel(job, PhraseKind.WAKE) is TrainingResult.Ok<*>)
    }

    @Test
    fun `an unscripted operation answers its documented default`() {
        val server = ScriptedTrainingServer()
        val job = JobId("j1")
        assertEquals(ok, server.uploadSample(PhraseKind.WAKE, "a", byteArrayOf(1)))
        assertEquals(ok, server.deleteMySamples())
        assertEquals(ok, server.deleteMyModel())
        assertEquals(TrainingResult.Unavailable, server.startJob())
        assertEquals(TrainingResult.Unavailable, server.jobStatus(job))
        assertEquals(TrainingResult.Unavailable, server.downloadModel(job, PhraseKind.SEND))
    }

    @Test
    fun `scripted start job results come back in order`() {
        val server = ScriptedTrainingServer()
        server.scriptStartJob(TrainingResult.Ok(JobId("j1")), TrainingResult.NotSignedIn)
        assertEquals(TrainingResult.Ok(JobId("j1")), server.startJob())
        assertEquals(TrainingResult.NotSignedIn, server.startJob())
    }
}
