package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** deleteAll and its order. Not compiled or run yet. */
class TrainingClientDeleteTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Looks at the store at the moment the server is asked to delete samples. */
    private class DeleteSpy(
        private val inner: ScriptedTrainingServer,
        private val store: SampleStore,
    ) : TrainingServer by inner {
        var markAtCall: Boolean? = null
        var localAtCall: Int = -1
        var jobAtCall: String? = "unset"

        override fun deleteMySamples(): TrainingResult<Unit> {
            markAtCall = store.serverDeletePending()
            localAtCall = store.list(PhraseKind.WAKE).size + store.list(PhraseKind.SEND).size +
                store.confirmed(PhraseKind.WAKE).size + store.confirmed(PhraseKind.SEND).size
            jobAtCall = store.loadJob()
            return inner.deleteMySamples()
        }
    }

    private fun fixture() = TrainingFixture(folder.root, 10, 10).also { it.saveJob("job-1") }

    @Test
    fun `deleting uploads nothing and calls only the two server deletes`() {
        val fx = fixture()
        assertEquals(DeleteResult.Done, fx.client.deleteAll())
        assertEquals(listOf("deleteMySamples", "deleteMyModel"), fx.server.calls)
    }

    @Test
    fun `a delete with the server up removes local data and clears the mark`() {
        val fx = fixture()
        assertTrue(fx.store.markConfirmed(PhraseKind.SEND, "c1") is StoreResult.Ok)
        assertEquals(DeleteResult.Done, fx.client.deleteAll())
        assertEquals(0, fx.local(PhraseKind.WAKE))
        assertEquals(0, fx.local(PhraseKind.SEND))
        assertEquals(emptySet<String>(), fx.store.confirmed(PhraseKind.SEND))
        assertNull(fx.store.loadJob())
        assertFalse(fx.store.serverDeletePending())
    }

    @Test
    fun `the mark is set and the local data is gone before the server is asked`() {
        val fx = fixture()
        assertTrue(fx.store.markConfirmed(PhraseKind.WAKE, "c1") is StoreResult.Ok)
        val spy = DeleteSpy(fx.server, fx.store)
        assertEquals(DeleteResult.Done, TrainingClient(fx.store, spy).deleteAll())
        assertEquals(true, spy.markAtCall)
        assertEquals(0, spy.localAtCall)
        assertNull(spy.jobAtCall)
    }

    @Test
    fun `a server network failure leaves the mark set and the local data gone`() {
        val fx = fixture()
        fx.server.scriptDeleteSamples(TrainingResult.Network)
        assertEquals(DeleteResult.ServerDeletePending(TrainingFailure.NETWORK), fx.client.deleteAll())
        assertEquals(0, fx.local(PhraseKind.WAKE))
        assertEquals(0, fx.local(PhraseKind.SEND))
        assertNull(fx.store.loadJob())
        assertTrue(fx.store.serverDeletePending())
    }

    @Test
    fun `both server deletes are called when the first fails and the first failure is reported`() {
        val fx = fixture()
        fx.server.scriptDeleteSamples(TrainingResult.Network)
        fx.server.scriptDeleteModel(TrainingResult.Unavailable)
        assertEquals(DeleteResult.ServerDeletePending(TrainingFailure.NETWORK), fx.client.deleteAll())
        assertEquals(listOf("deleteMySamples", "deleteMyModel"), fx.server.calls)
    }

    @Test
    fun `a failed model delete alone keeps the mark`() {
        val fx = fixture()
        fx.server.scriptDeleteModel(TrainingResult.Refused)
        assertEquals(DeleteResult.ServerDeletePending(TrainingFailure.REFUSED), fx.client.deleteAll())
        assertTrue(fx.store.serverDeletePending())
    }

    @Test
    fun `a later delete with the server up clears the mark`() {
        val fx = fixture()
        fx.server.scriptDeleteSamples(TrainingResult.Network)
        assertEquals(DeleteResult.ServerDeletePending(TrainingFailure.NETWORK), fx.client.deleteAll())
        assertEquals(DeleteResult.Done, fx.client.deleteAll())
        assertFalse(fx.store.serverDeletePending())
    }

    @Test
    fun `a later start retries the pending delete before anything else`() {
        val fx = fixture()
        fx.server.scriptDeleteSamples(TrainingResult.Network)
        assertEquals(DeleteResult.ServerDeletePending(TrainingFailure.NETWORK), fx.client.deleteAll())
        repeat(10) { fx.put(PhraseKind.WAKE) }
        repeat(10) { fx.put(PhraseKind.SEND) }
        val before = fx.server.calls.size
        fx.server.scriptStartJob(TrainingResult.Ok(JobId("job-2")))
        assertEquals(StartResult.Started(20), fx.client.startTraining())
        assertEquals(listOf("deleteMySamples", "deleteMyModel"), fx.server.calls.drop(before).take(2))
        assertFalse(fx.store.serverDeletePending())
    }

    @Test
    fun `local files are deleted even when the server is signed out`() {
        val fx = fixture()
        fx.server.scriptDeleteSamples(TrainingResult.NotSignedIn)
        assertEquals(DeleteResult.ServerDeletePending(TrainingFailure.NOT_SIGNED_IN), fx.client.deleteAll())
        assertEquals(0, fx.local(PhraseKind.WAKE))
        assertEquals(0, fx.local(PhraseKind.SEND))
        assertTrue(fx.store.serverDeletePending())
    }

    /** A fixture with no samples whose wake path is a plain file: the local delete fails but the ledger works. */
    private fun fixtureWithUndeletableWakeFolder(): TrainingFixture {
        val fx = TrainingFixture(folder.root, 0, 0)
        File(folder.root, "wake").writeText("not a folder")
        return fx
    }

    @Test
    fun `an unreadable ledger stops the delete before the server is called`() {
        val fx = TrainingFixture(folder.root, 10, 10)
        fx.breakLedger()
        assertEquals(DeleteResult.LocalFailed(null), fx.client.deleteAll())
        assertEquals(emptyList<String>(), fx.server.calls)
    }

    @Test
    fun `a mark that cannot be stored stops the delete before the server is called even if the server would fail`() {
        val fx = TrainingFixture(folder.root, 10, 10)
        fx.blockLedgerWrites()
        fx.server.scriptDeleteSamples(TrainingResult.Network)
        assertEquals(DeleteResult.LocalFailed(null), fx.client.deleteAll())
        assertEquals(emptyList<String>(), fx.server.calls)
    }

    @Test
    fun `a local failure with the mark stored is reported but the server delete is still tried`() {
        val fx = fixtureWithUndeletableWakeFolder()
        assertEquals(DeleteResult.LocalFailed(null), fx.client.deleteAll())
        assertEquals(listOf("deleteMySamples", "deleteMyModel"), fx.server.calls)
    }

    @Test
    fun `a local failure and a server failure with the mark stored are both reported`() {
        val fx = fixtureWithUndeletableWakeFolder()
        fx.server.scriptDeleteSamples(TrainingResult.Network)
        assertEquals(DeleteResult.LocalFailed(TrainingFailure.NETWORK), fx.client.deleteAll())
        assertEquals(2, fx.server.calls.size)
        assertTrue(fx.store.serverDeletePending())
    }
}
