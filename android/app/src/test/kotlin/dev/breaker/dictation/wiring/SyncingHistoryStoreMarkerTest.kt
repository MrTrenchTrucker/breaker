package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SyncingHistoryStore.save: mark-before-save, clear-after-enqueue, marker-write failure swallowed+counted, sync-off no marker. */
class SyncingHistoryStoreMarkerTest {

    private fun transcription(id: String): Transcription =
        Transcription(id = id, text = "t", source = TranscriptionSource.LOCAL, model = "m", durationMs = 0, createdAt = 0)

    private class FakeHistoryStore : HistoryStore {
        val saved = mutableListOf<Transcription>()
        var saveThrows: Throwable? = null
        override fun save(t: Transcription) { saved += t; saveThrows?.let { throw it } }
        override fun list(limit: Int): List<Transcription> = emptyList()
        override fun delete(id: String): Boolean = false
    }

    private class FakeSyncService : SyncService {
        val enqueued = mutableListOf<Transcription>()
        var enqueueThrows: Throwable? = null
        override fun enqueue(t: Transcription) { enqueueThrows?.let { throw it }; enqueued += t }
        override fun enqueueDelete(clientId: String) {}
        override fun pushPending(): dev.breaker.dictation.core.model.SyncReport =
            dev.breaker.dictation.core.model.SyncReport(0, 0)
    }

    private class FakeBackground : Background {
        override fun submit(block: () -> Unit) { block() }
    }

    private class FakeScheduler : SyncScheduler {
        override fun runSoon(block: () -> Unit) = Unit
        override fun runAfter(delayMs: Long, block: () -> Unit): SyncScheduler.Handle =
            object : SyncScheduler.Handle { override fun cancel() = Unit }
    }

    /** A capturing marker port that records marks and clears. */
    private class CapturingMarker : PendingSavePort {
        val marked = mutableListOf<Pair<String, Long>>()
        val cleared = mutableListOf<String>()
        var markThrows: Throwable? = null
        override fun markPendingSave(clientId: String, runToken: Long): Boolean {
            markThrows?.let { throw it }
            marked += Pair(clientId, runToken)
            return true
        }
        override fun clearPendingSave(clientId: String) = cleared.add(clientId).also { true }
    }

    private fun storeWith(
        marker: PendingSavePort = CapturingMarker(),
        syncOn: Boolean = true,
        innerSaveThrows: Throwable? = null,
        enqueueThrows: Throwable? = null,
    ): Triple<SyncingHistoryStore, FakeHistoryStore, FakeSyncService> {
        val inner = FakeHistoryStore().apply { saveThrows = innerSaveThrows }
        val service = FakeSyncService().apply { this.enqueueThrows = enqueueThrows }
        val worker = SyncWorker(
            service = service,
            background = FakeBackground(),
            scheduler = FakeScheduler(),
            sessionGate = { null },
        )
        val store = SyncingHistoryStore(
            inner = inner,
            syncService = service,
            worker = worker,
        )
        store.runToken = 1_700_000_000_000L
        store.marker = marker
        store.syncOn = syncOn
        return Triple(store, inner, service)
    }

    /** markPendingSave is called before inner.save: the order is mark, then save, then enqueue. */
    @Test
    fun `save marks before saving and clears after enqueue`() {
        val marker = CapturingMarker()
        val (store, inner, service) = storeWith(marker = marker)
        store.save(transcription("t-1"))
        assertEquals("mark is written before the save", listOf(Pair("t-1", 1_700_000_000_000L)), marker.marked)
        assertEquals("the row reached the inner store", listOf(transcription("t-1")), inner.saved)
        assertEquals("the marker is cleared after enqueue", listOf("t-1"), marker.cleared)
        assertEquals("the row was enqueued", listOf(transcription("t-1")), service.enqueued)
    }

    /** When the marker write throws, the failure is swallowed and counted, and the save still commits. */
    @Test
    fun `marker-write failure is swallowed and counted and never blocks the save`() {
        val marker = CapturingMarker().apply { markThrows = RuntimeException("marker store down") }
        val (store, inner, service) = storeWith(marker = marker)
        store.save(transcription("t-1")) // no exception escapes
        assertEquals("the marker-write failure is counted", 1, store.markerWriteFailures.get())
        assertEquals("the save still commits", listOf(transcription("t-1")), inner.saved)
        assertEquals("the row is still enqueued", listOf(transcription("t-1")), service.enqueued)
        assertEquals("the marker is cleared after enqueue", listOf("t-1"), marker.cleared)
    }

    /** When enqueue throws, the marker stays (not cleared). */
    @Test
    fun `enqueue failure leaves the marker in place`() {
        val marker = CapturingMarker()
        val (store, inner, service) = storeWith(marker = marker, enqueueThrows = RuntimeException("queue down"))
        store.save(transcription("t-1")) // no exception escapes
        assertEquals("the mark was written", listOf(Pair("t-1", 1_700_000_000_000L)), marker.marked)
        assertTrue("the marker was not cleared", marker.cleared.isEmpty())
        assertEquals("the row was saved", listOf(transcription("t-1")), inner.saved)
    }

    /** When the inner save throws, nothing is enqueued and no marker is cleared. */
    @Test
    fun `inner save throws and nothing is enqueued and the marker is not cleared`() {
        val marker = CapturingMarker()
        val boom = RuntimeException("database gone")
        val (store, inner, service) = storeWith(marker = marker, innerSaveThrows = boom)
        val thrown = assertThrows(RuntimeException::class.java) { store.save(transcription("t-1")) }
        assertSame("the same exception reaches the caller", boom, thrown)
        assertTrue("nothing was enqueued", service.enqueued.isEmpty())
        assertTrue("the marker was not cleared", marker.cleared.isEmpty())
        assertEquals("the mark was written before the failing save", listOf(Pair("t-1", 1_700_000_000_000L)), marker.marked)
    }

    /** When sync is off, no marker is written at all. */
    @Test
    fun `sync off writes no marker at all`() {
        val marker = CapturingMarker()
        val (store, inner, service) = storeWith(marker = marker, syncOn = false)
        store.save(transcription("t-1"))
        assertTrue("no marker is written when sync is off", marker.marked.isEmpty())
        assertTrue("no marker is cleared when sync is off", marker.cleared.isEmpty())
        assertEquals("the save still commits", listOf(transcription("t-1")), inner.saved)
        assertEquals("the row is still enqueued", listOf(transcription("t-1")), service.enqueued)
    }
}
