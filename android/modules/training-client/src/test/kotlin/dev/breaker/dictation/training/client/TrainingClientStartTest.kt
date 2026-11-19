package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The gates of startTraining and the order of its uploads. Not compiled or run yet. */
class TrainingClientStartTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture(wake: Int = 10, send: Int = 10) = TrainingFixture(folder.root, wake, send)

    private fun ids(prefix: String, count: Int): Set<String> = (1..count).map { "$prefix$it" }.toSet()

    private fun uploads(fx: TrainingFixture): List<String> = fx.server.calls.filter { it.startsWith("uploadSample") }

    @Test
    fun `building the client then polling and fetching with no job leaves the server call log empty`() {
        val fx = fixture(15, 15)
        assertEquals(PollResult.NoJob, fx.client.pollJob())
        assertEquals(FetchResult.NoJob, fx.client.fetchModel())
        assertEquals(emptyList<String>(), fx.server.calls)
        assertEquals(15, fx.local(PhraseKind.WAKE))
        assertEquals(15, fx.local(PhraseKind.SEND))
    }

    @Test
    fun `a start uploads every stored sample once confirms it deletes it locally and saves the job`() {
        val fx = fixture()
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-1")))
        assertEquals(StartResult.Started(20), fx.client.startTraining())
        assertEquals(20, uploads(fx).size)
        assertEquals(20, fx.server.uploadedIds.size)
        assertEquals("startJob", fx.server.calls.last())
        assertEquals(0, fx.local(PhraseKind.WAKE))
        assertEquals(0, fx.local(PhraseKind.SEND))
        assertEquals(ids("w", 10), fx.store.confirmed(PhraseKind.WAKE))
        assertEquals(ids("s", 10), fx.store.confirmed(PhraseKind.SEND))
        assertEquals("job-1", fx.store.loadJob())
        val expected = fx.encoder.expected(FloatArray(4) { 0.5f })
        assertTrue(fx.server.storedSamples.all { it.wav.contentEquals(expected) })
    }

    @Test
    fun `an upload failure leaves the local sample and the ledger without its id`() {
        val fx = fixture()
        fx.server.scriptUpload(TrainingResult.Network)
        assertEquals(StartResult.UploadFailed(TrainingFailure.NETWORK), fx.client.startTraining())
        assertEquals(10, fx.local(PhraseKind.WAKE))
        assertEquals(10, fx.local(PhraseKind.SEND))
        assertEquals(emptySet<String>(), fx.store.confirmed(PhraseKind.WAKE))
        assertEquals(1, uploads(fx).size)
        assertFalse(fx.server.calls.contains("startJob"))
    }

    @Test
    fun `a ledger write that fails keeps the local sample even though the server said ok`() {
        val fx = fixture()
        fx.blockLedgerWrites()
        assertEquals(StartResult.StoreFailed, fx.client.startTraining())
        assertEquals(10, fx.local(PhraseKind.WAKE))
        assertEquals(1, uploads(fx).size)
        assertEquals(1, fx.server.uploadedIds.size)
        assertFalse(fx.server.calls.contains("startJob"))
    }

    @Test
    fun `a partial failure then a retry completes because confirmed samples count toward the minimum`() {
        val fx = fixture()
        repeat(7) { fx.server.scriptUpload(TrainingResult.Ok(Unit)) }
        fx.server.scriptUpload(TrainingResult.Network)
        assertEquals(StartResult.UploadFailed(TrainingFailure.NETWORK), fx.client.startTraining())
        assertEquals(3, fx.local(PhraseKind.WAKE))
        assertEquals(7, fx.store.confirmed(PhraseKind.WAKE).size)
        val firstRun = fx.server.calls.size
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-2")))
        assertEquals(StartResult.Started(13), fx.client.startTraining())
        val secondRun = fx.server.calls.drop(firstRun).filter { it.startsWith("uploadSample") }
        assertEquals(13, secondRun.size)
        val confirmedBefore = fx.store.confirmed(PhraseKind.WAKE)
        assertEquals(10, confirmedBefore.size)
        assertEquals(20, fx.server.uploadedIds.size)
        assertEquals(0, fx.local(PhraseKind.WAKE))
        assertEquals("job-2", fx.store.loadJob())
    }

    @Test
    fun `an id the ledger already confirms is not uploaded again and its local file is deleted`() {
        val fx = fixture()
        assertTrue(fx.store.markConfirmed(PhraseKind.WAKE, "w1") is StoreResult.Ok)
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-1")))
        assertEquals(StartResult.Started(19), fx.client.startTraining())
        assertTrue(uploads(fx).none { it.startsWith("uploadSample WAKE w1 ") })
        assertEquals(StoreResult.NotFound, fx.store.read(PhraseKind.WAKE, "w1"))
        assertEquals(0, fx.local(PhraseKind.WAKE))
    }

    @Test
    fun `no samples at all uploads nothing`() {
        val fx = fixture(0, 0)
        assertEquals(StartResult.TooFewSamples(PhraseKind.WAKE, 0, 10), fx.client.startTraining())
        assertEquals(emptyList<String>(), fx.server.calls)
    }

    @Test
    fun `too few wake samples uploads nothing`() {
        val fx = fixture(9, 10)
        assertEquals(StartResult.TooFewSamples(PhraseKind.WAKE, 9, 10), fx.client.startTraining())
        assertEquals(emptyList<String>(), fx.server.calls)
        assertEquals(9, fx.local(PhraseKind.WAKE))
    }

    @Test
    fun `too few send samples uploads nothing even though the wake samples are enough`() {
        val fx = fixture(10, 9)
        assertEquals(StartResult.TooFewSamples(PhraseKind.SEND, 9, 10), fx.client.startTraining())
        assertEquals(emptyList<String>(), fx.server.calls)
        assertEquals(10, fx.local(PhraseKind.WAKE))
    }

    @Test
    fun `samples that are both local and confirmed count once toward the minimum`() {
        val fx = fixture(5, 10)
        for (id in ids("w", 5)) assertTrue(fx.store.markConfirmed(PhraseKind.WAKE, id) is StoreResult.Ok)
        assertEquals(StartResult.TooFewSamples(PhraseKind.WAKE, 5, 10), fx.client.startTraining())
        assertEquals(emptyList<String>(), fx.server.calls)
    }

    @Test
    fun `confirmed ids with no local file count toward the minimum`() {
        val fx = fixture(5, 10)
        for (id in ids("c", 5)) assertTrue(fx.store.markConfirmed(PhraseKind.WAKE, id) is StoreResult.Ok)
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-1")))
        assertEquals(StartResult.Started(15), fx.client.startTraining())
        assertEquals(15, uploads(fx).size)
        assertEquals("job-1", fx.store.loadJob())
    }

    @Test
    fun `a ledger replaced by a directory makes the start fail closed with nothing sent`() {
        val fx = fixture()
        fx.breakLedger()
        assertEquals(StartResult.StoreFailed, fx.client.startTraining())
        assertEquals(emptyList<String>(), fx.server.calls)
        assertEquals(10, fx.local(PhraseKind.WAKE))
        assertEquals(10, fx.local(PhraseKind.SEND))
    }

    @Test
    fun `a dangling symlink in the place of the ledger stops the start before any server call`() {
        val fx = fixture()
        symlinkOrSkip(File(folder.root, "ledger.txt"), File(folder.root, "nowhere"))
        assertEquals(StartResult.StoreFailed, fx.client.startTraining())
        assertEquals(emptyList<String>(), fx.server.calls)
        assertEquals(10, fx.local(PhraseKind.WAKE))
        assertEquals(10, fx.local(PhraseKind.SEND))
    }
}
