package dev.breaker.dictation.wiring

import dev.breaker.dictation.SystemClockAdapter
import dev.breaker.dictation.history.SqliteHistoryStore
import dev.breaker.dictation.sync.QueuedSyncService
import dev.breaker.dictation.sync.SaveBackfillWalk
import dev.breaker.dictation.sync.backfillPendingSaves
import dev.breaker.dictation.sync.clearPendingSave
import dev.breaker.dictation.sync.markPendingSave
import dev.breaker.dictation.sync.pendingSaveIds

/**
 * The one run token for this process: process-start epoch millis, read once and kept in a val.
 * Lives here, not in BreakerApp, so the application class stays under the line cap and the token
 * is still read exactly once per process.
 */
internal object RunTokenHolder {
    val token: Long = SystemClockAdapter.nowEpochMillis()
}

/**
 * The start-up save-backfill walk state, kept out of BreakerApp so the application class stays
 * under the line cap. It holds the decorator bound at start (so the walk can read its
 * marker-write-failure count without a third BreakerApp reference) and the last walk counters.
 */
internal object SaveBackfillWiring {
    /** The decorator attached at start, so the walk can read its marker-write-failure count. */
    internal var attachedStore: SyncingHistoryStore? = null
        private set

    /** The last save-backfill walk result, surfaced through the start-up log channel: numbers only. */
    internal var lastSaveBackfillWalk: SaveBackfillWalk? = null

    /** The last save-path marker-write-failure count, surfaced through the start-up log channel. */
    internal var lastMarkerWriteFailures: Int = 0

    internal fun bind(store: SyncingHistoryStore) {
        attachedStore = store
    }
}

/**
 * Attaches the real pending-save marker port, the one run token and the syncOn decision to the
 * decorator, once, and returns it so BreakerApp keeps its one-line construction
 * (`SyncingHistoryStore(inner = history, syncService = syncService, worker = syncWorker)` plus
 * this chained call). The port delegates every marker write and clear to the queue service; the
 * decorator's marker, runToken and syncOn are set here and are no-op defaults until then.
 */
internal fun SyncingHistoryStore.attach(syncService: QueuedSyncService, syncOn: Boolean): SyncingHistoryStore {
    val port = object : PendingSavePort {
        override fun markPendingSave(clientId: String, runToken: Long): Boolean = syncService.markPendingSave(clientId, runToken)
        override fun clearPendingSave(clientId: String): Boolean = syncService.clearPendingSave(clientId)
    }
    this.marker = port
    this.runToken = RunTokenHolder.token
    this.syncOn = syncOn
    SaveBackfillWiring.bind(this)
    return this
}

/**
 * The one start-up call: runs the save-backfill walk when sync is on, stores the counters and
 * emits the one fixed anomaly warning through [logAnomaly] only when the walk re-queued or could
 * not re-queue a saved take. Gated by syncOn here so BreakerApp stays a single call; a normal
 * start (no re-queues, only deferred live markers) emits nothing.
 */
internal fun runSaveBackfill(
    syncOn: Boolean,
    syncService: QueuedSyncService,
    history: SqliteHistoryStore,
    logAnomaly: (Exception) -> Unit,
) {
    if (!syncOn) return
    val walk = backfillPendingSaves(
        runToken = RunTokenHolder.token,
        pendingSaveIdsPage = { limit -> syncService.pendingSaveIds(limit, null) },
        pendingSaveIdsPageAfter = { lastId, limit -> syncService.pendingSaveIds(limit, lastId) },
        rowById = { clientId -> history.rowById(clientId) },
        enqueue = { syncService.enqueue(it) },
        clearPendingSave = { syncService.clearPendingSave(it) },
    )
    SaveBackfillWiring.lastSaveBackfillWalk = walk
    SaveBackfillWiring.lastMarkerWriteFailures = SaveBackfillWiring.attachedStore?.markerWriteFailures?.get() ?: 0
    if (saveBackfillAnomaly(walk)) logAnomaly(Exception())
}

/** True when the start-up walk re-queued or could not re-queue a saved take: the only case that emits the fixed anomaly warning. A normal walk (only deferred live markers, or nothing) returns false and logs nothing. */
internal fun saveBackfillAnomaly(walk: SaveBackfillWalk): Boolean = walk.reQueued + walk.markerFailures > 0