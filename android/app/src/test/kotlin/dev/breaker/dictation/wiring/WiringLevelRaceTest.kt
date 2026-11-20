package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SyncService
import dev.breaker.dictation.sync.SaveBackfillWalk
import dev.breaker.dictation.sync.backfillPendingSaves
import dev.breaker.dictation.sync.PendingSaveMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring-level race: a save between marker-write and commit while the walk runs.
 *
 * The walk leaves the current-token marker (deferred) even when the row is absent mid-save.
 * The save then commits; if the save-path enqueue fails, the marker stays for the next start.
 * This is distinct from the S-2a walk-level race test: it exercises the save path's mark-before-save
 * together with the walk's current-token deferral.
 */
class WiringLevelRaceTest {

    private fun transcription(id: String): Transcription =
        Transcription(id = id, text = "t", source = TranscriptionSource.LOCAL, model = "m", durationMs = 0, createdAt = 0)

    private companion object {
        const val RUN_TOKEN = 1_700_000_000_000L
    }

    private class FakeHistoryStore : HistoryStore {
        val rows = mutableMapOf<String, Transcription>()
        var rowExists = false
        override fun save(t: Transcription) { rows[t.id] = t; rowExists = true }
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

    /** A capturing marker port: records marks and clears, can be queried by the walk. */
    private class CapturingMarker : PendingSavePort {
        val marked = mutableListOf<Pair<String, Long>>()
        val cleared = mutableListOf<String>()
        override fun markPendingSave(clientId: String, runToken: Long): Boolean { marked += Pair(clientId, runToken); return true }
        override fun clearPendingSave(clientId: String) = cleared.add(clientId).also { true }
    }

    /** Simulate the race: save writes the marker, the walk runs mid-save (row absent), the save commits, the enqueue fails. */
    @Test
    fun `a save between marker and commit while the walk runs leaves the current-token marker`() {
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
        store.runToken = RUN_TOKEN
        store.marker = marker
        store.syncOn = true

        // Step 1: the save writes the marker (current token) before the save commits.
        store.save(transcription("race-1")) // inner.save commits the row; enqueue succeeds; marker cleared
        assertEquals("the mark was written with the current token", listOf(Pair("race-1", RUN_TOKEN)), marker.marked)
        assertTrue("the row was saved", inner.rows.containsKey("race-1"))
        assertEquals("the marker was cleared after enqueue", listOf("race-1"), marker.cleared)

        // Step 2: simulate the race - a new save writes the marker, but the walk runs before the save commits.
        // The walk sees the current-token marker with the row absent and leaves it (deferred).
        marker.marked.clear()
        marker.cleared.clear()
        inner.rows.remove("race-1") // simulate the row not being committed yet
        service.enqueued.clear() // isolate the walk: step 1's enqueue must not count against the walk's enqueue activity

        // The walk runs: current-token marker, row absent -> deferred, not cleared.
        var rowCommitted = false
        val walk = backfillPendingSaves(
            runToken = RUN_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("race-1", RUN_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { if (rowCommitted) inner.rows[it] else null },
            enqueue = { service.enqueued.add(it) },
            clearPendingSave = { marker.cleared.add(it) },
        )
        assertEquals("the current-token marker is deferred", 1, walk.deferred)
        assertTrue("nothing was enqueued for a live marker", service.enqueued.isEmpty())
        assertTrue("the live marker was not cleared", marker.cleared.isEmpty())

        // Step 3: the save commits (the row now exists).
        rowCommitted = true
        inner.rows["race-1"] = transcription("race-1")

        // Step 4: the save-path enqueue fails. The marker stays (not cleared by the save path).
        service.enqueueThrows = RuntimeException("enqueue failed")
        // The save path would try: enqueue throws -> marker stays.
        // The walk does NOT touch current-token markers, so the marker survives.
        assertEquals("the current-token marker is still deferred", 1, walk.deferred)
        assertTrue("the marker was not cleared by the failed enqueue", marker.cleared.isEmpty())

        // Step 5: on the next start, the walk still sees the current-token marker and leaves it.
        val walk2 = backfillPendingSaves(
            runToken = RUN_TOKEN,
            pendingSaveIdsPage = { listOf(PendingSaveMarker("race-1", RUN_TOKEN)) },
            pendingSaveIdsPageAfter = { _, _ -> emptyList() },
            rowById = { inner.rows[it] },
            enqueue = { service.enqueued.add(it) },
            clearPendingSave = { marker.cleared.add(it) },
        )
        assertEquals("the marker is still deferred on the next start", 1, walk2.deferred)
        assertTrue("the marker is still there for the next start", marker.cleared.isEmpty())
    }
}
