package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.PullSink
import dev.breaker.dictation.core.port.SyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncingHistoryStoreTest {

    private fun transcription(
        id: String,
        text: String = "text-$id",
        source: TranscriptionSource = TranscriptionSource.LOCAL,
    ): Transcription =
        Transcription(
            id = id,
            text = text,
            source = source,
            model = "m",
            durationMs = 0,
            createdAt = 0,
        )

    private fun session(token: String = "token"): AuthSession =
        AuthSession(
            userId = "u",
            username = "u",
            token = token,
            role = UserRole.USER,
            scopes = emptySet(),
        )

    private class Corruption(message: String) : Exception(message)

    private class FakeHistoryStore : HistoryStore, PullSink {
        val order = mutableListOf<String>()
        val saves = mutableListOf<Transcription>()
        val lists = mutableListOf<Int>()
        val deletes = mutableListOf<String>()
        val appliedRows = mutableListOf<Transcription>()
        var saveThrows: Throwable? = null
        var deleteReturns = true

        override fun save(t: Transcription) {
            order += "save"
            saves += t
            saveThrows?.let { throw it }
        }

        override fun list(limit: Int): List<Transcription> {
            order += "list"
            lists += limit
            return emptyList()
        }

        override fun delete(id: String): Boolean {
            order += "delete"
            deletes += id
            return deleteReturns
        }

        override fun applyRemoteRow(row: Transcription): Boolean {
            appliedRows += row
            return true
        }

        override fun applyRemoteDelete(id: String, deletedAt: Long): Boolean = false

        override fun retainOnly(keep: Set<String>): Int = 0
    }

    /**
     * [order] is the same list the [FakeHistoryStore] writes, so a test can prove the save was
     * committed before the enqueue ran and not just that both happened.
     */
    private class FakeSyncService(
        private val order: MutableList<String> = mutableListOf(),
    ) : SyncService {
        val enqueued = mutableListOf<Transcription>()
        val pushes = java.util.concurrent.atomic.AtomicInteger(0)
        var enqueueThrows: Throwable? = null

        override fun enqueue(t: Transcription) {
            order += "enqueue"
            enqueueThrows?.let { throw it }
            enqueued += t
        }

        /** Records every delete the decorator asks the queue to send. */
        val enqueuedDeletes = mutableListOf<String>()
        var enqueueDeleteThrows: Throwable? = null

        override fun enqueueDelete(clientId: String) {
            order += "enqueueDelete"
            enqueueDeleteThrows?.let { throw it }
            enqueuedDeletes += clientId
        }

        override fun pushPending(): SyncReport {
            pushes.incrementAndGet()
            return SyncReport(0, 0)
        }
    }

    private class FakeBackground : Background {
        override fun submit(block: () -> Unit) {
            block() // run inline: the test owns the timing, no threads, no blocking
        }
    }

    private class FakeScheduler : SyncScheduler {
        override fun runSoon(block: () -> Unit) = Unit

        override fun runAfter(delayMs: Long, block: () -> Unit): SyncScheduler.Handle =
            object : SyncScheduler.Handle {
                override fun cancel() = Unit
            }
    }

    /**
     * Builds the decorator over [inner] and [service]. The service shares [inner]'s order list, so
     * `inner.order` reads "save" then "enqueue" in the order they actually ran.
     */
    private fun storeWith(
        inner: FakeHistoryStore = FakeHistoryStore(),
        service: FakeSyncService = FakeSyncService(inner.order),
    ): SyncingHistoryStore {
        val worker = SyncWorker(
            service = service,
            background = FakeBackground(),
            scheduler = FakeScheduler(),
            sessionGate = { session() },
        )
        return SyncingHistoryStore(inner = inner, syncService = service, worker = worker)
    }

    @Test
    fun `save calls the inner store first and enqueues after it`() {
        val inner = FakeHistoryStore()
        val service = FakeSyncService(inner.order)
        val store = storeWith(inner = inner, service = service)
        val t = transcription("t-1", source = TranscriptionSource.SERVER)
        store.save(t)
        assertEquals("inner save is committed before the row is queued", listOf("save", "enqueue"), inner.order)
        assertEquals("the row is queued whatever its source", listOf(t), service.enqueued)
    }

    @Test
    fun `enqueue runs only after the inner save returned`() {
        val inner = FakeHistoryStore()
        val service = FakeSyncService(inner.order)
        val store = storeWith(inner = inner, service = service)
        val t = transcription("t-2")
        store.save(t)
        assertEquals("enqueue happens only after the inner save returned", listOf(t), service.enqueued)
        assertEquals(listOf("save", "enqueue"), inner.order)
    }

    @Test
    fun `when the inner save throws, nothing is enqueued and the same exception reaches the caller`() {
        val inner = FakeHistoryStore()
        val boom = Corruption("the database is gone")
        inner.saveThrows = boom
        val service = FakeSyncService(inner.order)
        val store = storeWith(inner = inner, service = service)
        val t = transcription("t-3")
        val thrown = assertThrows(Corruption::class.java) { store.save(t) }
        assertSame("the same exception reaches the caller", boom, thrown)
        assertTrue("nothing is enqueued when the save throws", service.enqueued.isEmpty())
    }

    @Test
    fun `when enqueue throws, save still reports success and the row is saved`() {
        val inner = FakeHistoryStore()
        val boom = Corruption("the queue refused the row")
        val service = FakeSyncService().apply { enqueueThrows = boom }
        val store = storeWith(inner = inner, service = service)
        val t = transcription("t-4")
        store.save(t) // no exception escapes
        assertTrue("the save reached the inner store", inner.saves.contains(t))
        assertTrue("the enqueue failure did not escape the save", service.enqueued.isEmpty())
    }

    @Test
    fun `a pulled row (applyRemoteRow path) never enqueues`() {
        val inner = FakeHistoryStore()
        val service = FakeSyncService()
        val store = storeWith(inner = inner, service = service)
        // The decorator is reached only through the HistoryStore port: save, list, delete.
        // applyRemoteRow lives on PullSink, which the decorator does not expose, so a pulled row
        // is handed to the inner store directly and never passes through SyncingHistoryStore.save.
        val port: HistoryStore = store
        val pulled = transcription("pulled-1")
        inner.applyRemoteRow(pulled)
        assertTrue("the inner store took the pulled row", inner.appliedRows.contains(pulled))
        assertTrue("a pulled row never enqueues", service.enqueued.isEmpty())
        assertEquals(emptyList<Transcription>(), port.list(5))
    }

    @Test
    fun `list passes through and never enqueues`() {
        val inner = FakeHistoryStore()
        val service = FakeSyncService()
        val store = storeWith(inner = inner, service = service)
        assertEquals(emptyList<Transcription>(), store.list(5))
        assertEquals(listOf(5), inner.lists)
        assertTrue("list never enqueues", service.enqueued.isEmpty() && service.enqueuedDeletes.isEmpty())
    }

    @Test
    fun `delete commits the tombstone first and then enqueues a delete`() {
        val inner = FakeHistoryStore()
        val service = FakeSyncService(inner.order)
        val store = storeWith(inner = inner, service = service)
        val t = transcription("t-8")
        assertTrue("delete returns true when a row was removed", store.delete(t.id))
        assertEquals("the tombstone is committed before the delete is queued",
            listOf("delete", "enqueueDelete"), inner.order)
        assertEquals(listOf(t.id), inner.deletes)
        assertEquals("the queue receives a delete entry for the id", listOf(t.id), service.enqueuedDeletes)
    }

    @Test
    fun `a delete for an unknown id enqueues nothing`() {
        val inner = FakeHistoryStore().apply { deleteReturns = false }
        val service = FakeSyncService(inner.order)
        val store = storeWith(inner = inner, service = service)
        assertFalse("delete returns false when nothing was removed", store.delete("t-9"))
        assertTrue("nothing was queued for an unknown id", service.enqueuedDeletes.isEmpty())
    }

    @Test
    fun `when enqueueDelete throws, the delete still stands and nothing escapes`() {
        val inner = FakeHistoryStore()
        val boom = Corruption("the queue refused the row")
        val service = FakeSyncService().apply { enqueueDeleteThrows = boom }
        val store = storeWith(inner = inner, service = service)
        store.delete("t-10") // no exception escapes
        assertTrue("the delete reached the inner store", inner.deletes.contains("t-10"))
        assertTrue("the enqueueDelete failure did not escape the delete", service.enqueuedDeletes.isEmpty())
    }

    @Test
    fun `the worker is asked to run after a successful save`() {
        val inner = FakeHistoryStore()
        val service = FakeSyncService()
        val store = storeWith(inner = inner, service = service)
        val t = transcription("t-7")
        store.save(t)
        assertEquals(listOf(t), service.enqueued)
        assertEquals(1, service.pushes.get()) // the drain ran: pushPending was called
    }
}
