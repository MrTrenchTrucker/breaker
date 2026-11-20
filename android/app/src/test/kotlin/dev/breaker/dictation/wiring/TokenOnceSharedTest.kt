package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SyncService
import dev.breaker.dictation.sync.backfillPendingSaves
import dev.breaker.dictation.sync.PendingSaveMarker
import org.junit.Assert.assertEquals
import org.junit.Test

/** The run token is created once in app wiring and the same val reaches both the save path and the start-up walk. */
class TokenOnceSharedTest {

    private fun transcription(id: String): Transcription =
        Transcription(id = id, text = "t", source = TranscriptionSource.LOCAL, model = "m", durationMs = 0, createdAt = 0)

    private class FakeHistoryStore : HistoryStore {
        override fun save(t: Transcription) {}
        override fun list(limit: Int): List<Transcription> = emptyList()
        override fun delete(id: String): Boolean = false
    }

    private class FakeSyncService : SyncService {
        override fun enqueue(t: Transcription) {}
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

    /** A capturing marker port that records the run token it was asked to write. */
    private class CapturingMarker : PendingSavePort {
        var lastToken: Long? = null
        override fun markPendingSave(clientId: String, runToken: Long): Boolean { lastToken = runToken; return true }
        override fun clearPendingSave(clientId: String) = true
    }

    /** The store is built once with one token; the save path and the walk receive the same val. */
    @Test
    fun `the run token is created once and shared by save path and walk`() {
        val token = 1_700_000_000_000L
        val marker = CapturingMarker()
        val inner = FakeHistoryStore()
        val service = FakeSyncService()
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
        store.runToken = token
        store.marker = marker
        store.syncOn = true
        // Save path uses the token.
        store.save(transcription("t-1"))
        assertEquals("save path received the created token", token, marker.lastToken)
        // The walk receives the SAME token: a current-token marker is deferred (the walk compares by equality).
        val walk = backfillPendingSaves(
            runToken = token,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("t-1", token)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { null },
            enqueue = { },
            clearPendingSave = { },
        )
        assertEquals("the walk received the created token and deferred its current-token marker", 1, walk.deferred)
        // The token is read exactly once: constructing the store a second time with a different token
        // does not change the first store's token.
        val marker2 = CapturingMarker()
        val store2 = SyncingHistoryStore(
            inner = inner,
            syncService = service,
            worker = worker,
        )
        store2.runToken = token + 1
        store2.marker = marker2
        store2.syncOn = true
        store2.save(transcription("t-2"))
        assertEquals("first store's token unchanged", token, marker.lastToken)
        assertEquals("second store's token differs", token + 1, marker2.lastToken ?: -1L)
    }
}
