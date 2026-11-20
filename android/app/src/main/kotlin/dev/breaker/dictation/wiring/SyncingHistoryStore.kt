package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SyncService
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [HistoryStore] decorator: every save is committed to the inner store first and only then
 * queued for sync. The order is the guarantee: nothing is enqueued before the save, and nothing
 * is enqueued at all when the save throws.
 *
 * This class takes no monitor, starts no background work and reads no clock, so it stays inside
 * the privacy scan's rules for `wiring/` (AppPrivacyScanTest). `list` passes straight through
 * and never enqueues. `delete` commits the tombstone first and only then queues a delete for
 * the id; the queue removes any queued save of the same id first, so the delete always wins.
 * Pulled rows never reach this decorator: `applyRemoteRow` lives on the `PullSink` port
 * (SqliteHistoryStore.kt:232), which the `HistoryStore` port does not expose, so a value typed
 * as `HistoryStore` cannot be asked to apply a pulled row at all.
 *
 * Before the inner save, a pending-save marker is written (when sync is on) through the narrow
 * [PendingSavePort]. A marker-write failure is swallowed and counted in [markerWriteFailures]
 * and never blocks or fails the save. After enqueue success the marker is cleared; if the
 * enqueue throws the marker stays for the start-up walk to reconcile.
 */
class SyncingHistoryStore(
    /** The store the text is committed to first. */
    private val inner: HistoryStore,
    /** The queue the transcription is handed to after a successful save. */
    private val syncService: SyncService,
    /** Asked to run a drain after a successful save. */
    private val worker: SyncWorker,
) : HistoryStore {

    /** Counts marker-write failures swallowed so the save never blocks. Numbers only. */
    internal val markerWriteFailures: AtomicInteger = AtomicInteger(0)

    /** The one run token for this process; set once by [attach]. No-op default 0L until then. */
    internal var runToken: Long = 0L

    /** Narrow marker port; set once by [attach]. No-op default so a bare construction compiles. */
    internal var marker: PendingSavePort = NoOpPendingSavePort

    /** When sync is not allowed no marker is written or cleared at all (release builds). */
    internal var syncOn: Boolean = true

    override fun save(transcription: Transcription) {
        // Sync-off gate: when sync is not allowed no marker is written at all.
        if (syncOn) {
            try {
                marker.markPendingSave(transcription.id, runToken)
            } catch (e: Exception) {
                // Swallowed and counted: the save must never block or fail because the marker
                // could not be written. A later enqueue failure is then invisible to the walk;
                // the counter is the signal. The save still commits below.
                markerWriteFailures.incrementAndGet()
            }
        }
        // The save is committed first. If it throws, this exception propagates unchanged and
        // nothing is enqueued: a failed save never reaches the sync queue.
        inner.save(transcription)
        // The save already happened. If the queue refuses the row, the save stands: the text
        // is saved locally and this failure is swallowed instead of undoing or failing the save.
        try {
            syncService.enqueue(transcription)
            // On enqueue success, clear the marker — but only when sync is on. A release build
            // (syncOn = false) does no marker write and no marker clear at all. If the clear
            // throws, the marker stays and the next start re-queues the row once (idempotent).
            if (syncOn) {
                try {
                    marker.clearPendingSave(transcription.id)
                } catch (e: Exception) {
                    // Swallowed: the marker stays, the next start re-queues once.
                }
            }
        } catch (e: Exception) {
            // Swallowed: the row is saved in history but not queued. The save stands: an enqueue
            // failure never undoes the save. The marker stays for the start-up walk to reconcile.
        }
        // Only a successful save asks the worker to run.
        worker.onSave()
    }

    override fun list(limit: Int): List<Transcription> = inner.list(limit)

    override fun delete(id: String): Boolean {
        // The tombstone commits first. If it throws or removes nothing, nothing is enqueued:
        // a failed delete never reaches the sync queue.
        val deleted = inner.delete(id)
        if (!deleted) return false
        // The tombstone already happened. If the queue refuses the row, the delete stands: the
        // tombstone is local and the delete stays queued for the start-up backfill or a later
        // drain; a queue failure never undoes the delete.
        try {
            syncService.enqueueDelete(id)
        } catch (e: Exception) {
            // Swallowed: the tombstone stands but the delete is not queued yet. The start-up
            // backfill re-queues any tombstone whose id is not already in the queue.
        }
        // Only a successful delete asks the worker to run.
        worker.onSave()
        return true
    }
}

/** The narrow pending-save marker port: write a marker before the save, clear it after enqueue. */
interface PendingSavePort {
    /** Writes a marker for [clientId] stamped with [runToken] (insert-if-absent). True when added. */
    fun markPendingSave(clientId: String, runToken: Long): Boolean
    /** Removes the marker for [clientId]. True when removed. */
    fun clearPendingSave(clientId: String): Boolean
}

/** No-op marker port: the save path compiles and runs without a real marker store. */
object NoOpPendingSavePort : PendingSavePort {
    override fun markPendingSave(clientId: String, runToken: Long): Boolean = false
    override fun clearPendingSave(clientId: String): Boolean = false
}
