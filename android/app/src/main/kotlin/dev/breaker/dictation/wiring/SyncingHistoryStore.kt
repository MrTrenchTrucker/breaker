package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.SyncService

/**
 * A [HistoryStore] decorator: every save is committed to the inner store first and only then
 * queued for sync. The order is the guarantee: nothing is enqueued before the save, and nothing
 * is enqueued at all when the save throws.
 *
 * This class takes no monitor, starts no background work and reads no clock, so it stays inside
 * the privacy scan's rules for `wiring/` (AppPrivacyScanTest). `list` and `delete` pass straight
 * through and never enqueue. Pulled rows never reach this decorator: `applyRemoteRow` lives on
 * the `PullSink` port (SqliteHistoryStore.kt:232), which the `HistoryStore` port does not expose,
 * so a value typed as `HistoryStore` cannot be asked to apply a pulled row at all.
 */
class SyncingHistoryStore(
    /** The store the text is committed to first. */
    private val inner: HistoryStore,
    /** The queue the transcription is handed to after a successful save. */
    private val syncService: SyncService,
    /** Asked to run a drain after a successful save. */
    private val worker: SyncWorker,
) : HistoryStore {
    override fun save(transcription: Transcription) {
        // The save is committed first. If it throws, this exception propagates unchanged and
        // nothing is enqueued: a failed save never reaches the sync queue.
        inner.save(transcription)
        // The save already happened. If the queue refuses the row, the save stands: the text
        // is saved locally and this failure is swallowed instead of undoing or failing the save.
        try {
            syncService.enqueue(transcription)
        } catch (e: Exception) {
            // Swallowed: the row is saved in history but not queued. The save stands: an enqueue
            // failure never undoes the save and nothing queues the row later.
        }
        // Only a successful save asks the worker to run.
        worker.onSave()
    }

    override fun list(limit: Int): List<Transcription> = inner.list(limit)

    override fun delete(id: String): Boolean = inner.delete(id)
}
