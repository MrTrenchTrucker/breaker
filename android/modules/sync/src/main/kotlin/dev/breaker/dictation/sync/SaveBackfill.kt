package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import kotlinx.coroutines.CancellationException

/**
 * The result of the bounded save-backfill walk over pending-save markers.
 *
 * [reQueued] markers of an old token had a live history row: they were re-enqueued and their marker cleared.
 * [markerFailures] markers of an old token whose row-read, enqueue or clear threw: the marker stays for the next start.
 * [deferred] markers carry this process's run token: they are live (a save of this run may be between the marker
 * write and the save commit, or its enqueue may have failed this run), so the walk leaves them and never touches them.
 * [complete] is true only when the walk reached the end of the markers (an empty or short page).
 */
public data class SaveBackfillWalk(
    val reQueued: Int,
    val markerFailures: Int,
    val deferred: Int,
    val complete: Boolean,
)

/**
 * Walk pending-save markers in pages and reconcile each with the history row.
 *
 * Condition 2, mechanism (b): the walk takes this process's [runToken] and compares each marker's
 * run token by EQUALITY, not by clock ordering.
 *
 * For each marker:
 * - [marker.runToken] == [runToken]: a marker of this run. It is live - a save of this run may be
 *   between the marker write and the save commit, or its enqueue may have failed this run. The walk
 *   leaves it, never enqueues and never clears it, and counts it as [deferred]. A backward wall-clock
 *   jump must not make a live marker look old; [created_at] is information only and nothing depends on it.
 * - any other token: an old marker (a previous run died).
 *   - Row exists -> [enqueue](row) then [clearPendingSave](id): the stuck save is re-queued (reQueued++).
 *   - Row absent -> [clearPendingSave](id): the save never committed, the orphan marker is discarded.
 *   - If [rowById], [enqueue] or [clearPendingSave] throws, the marker stays, the failure is counted
 *     as [markerFailures], and the walk continues with the next marker. [CancellationException] is
 *     rethrown: interrupts are never swallowed silently.
 *
 * The app wiring takes ONE token per process: the process-start epoch millis read once, kept in a val
 * (S-2b creates it), passed to markPendingSave and to this walk. No random source is used (the app
 * privacy scan flags Random/SecureRandom); two processes starting in the same millisecond cannot both
 * be alive, so the process-start token identifies this run.
 *
 * Bounded by [limit] markers per page and [maxPages] pages, the same shape as
 * [backfillAllUserTombstonesChecked]; an incomplete walk is reported via [complete], never silently
 * truncated. Markers are never pushed: [enqueue] is the save-path enqueue, and the walk drains only
 * pending_save, never sync_queue.
 */
public fun backfillPendingSaves(
    /** This process's run token: process-start epoch millis, read once and kept in a val by the app wiring (S-2b). */
    runToken: Long,
    /** Page 1: up to [limit] pending-save markers, oldest first (by client id). */
    pendingSaveIdsPage: (limit: Int) -> List<PendingSaveMarker>,
    /** The next page: markers above [lastId.clientId], up to [limit], oldest first. */
    pendingSaveIdsPageAfter: (lastId: String, limit: Int) -> List<PendingSaveMarker>,
    /** Reads the history row by client id, or null when the row is absent. The narrow row-reader port. */
    rowById: (clientId: String) -> Transcription?,
    /** Queues [row] for the next push. */
    enqueue: (Transcription) -> Unit,
    /** Removes the marker for [clientId]. */
    clearPendingSave: (clientId: String) -> Unit,
    limit: Int = 1000,
    maxPages: Int = 1000,
): SaveBackfillWalk {
    var lastId = ""
    var pages = 0
    var reQueued = 0
    var markerFailures = 0
    var deferred = 0
    var complete = false
    while (pages < maxPages) {
        val markers = if (lastId.isEmpty()) pendingSaveIdsPage(limit) else pendingSaveIdsPageAfter(lastId, limit)
        if (markers.isEmpty()) {
            complete = true
            break
        }
        for (marker in markers) {
            if (marker.runToken == runToken) {
                // Live marker of this run: leave it, never enqueue, never clear, count as deferred.
                deferred += 1
                continue
            }
            try {
                val row = rowById(marker.clientId)
                if (row != null) {
                    enqueue(row)
                    clearPendingSave(marker.clientId)
                    reQueued += 1
                } else {
                    // Orphan: the save never committed; drop the marker.
                    clearPendingSave(marker.clientId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // One marker's row-read/enqueue/clear must not abort the whole walk: the marker
                // stays for the next start and the walk continues with the next marker.
                markerFailures += 1
            }
        }
        lastId = markers.last().clientId
        pages += 1
        // A short page is the last page: keyset pagination returns at most [limit] markers per page,
        // so fewer than [limit] means there is no page after this one.
        if (markers.size < limit) {
            complete = true
            break
        }
    }
    return SaveBackfillWalk(reQueued = reQueued, markerFailures = markerFailures, deferred = deferred, complete = complete)
}
