package dev.breaker.dictation.wiring

import dev.breaker.dictation.host.runBoundedOnOwnThread

/**
 * Backfill pre-existing tombstones so each one has a delete queued.
 *
 * For every pending tombstone id whose id is not already in the sync queue, enqueue a delete
 * entry. Idempotent: a re-run is a no-op because [enqueueDelete] keeps the first entry
 * (INSERT OR IGNORE) and [isQueued] then reports the id as queued.
 *
 * The app calls this at start-up next to the retention purge, so tombstones that predate this
 * change still reach the server.
 */
fun backfillPendingTombstones(
    /** The ids of the tombstones waiting to travel, newest first. */
    pendingTombstoneIds: () -> List<String>,
    /** True when a row with [clientId] is already queued as a delete. A queued save does not count. */
    isDeleteQueued: (clientId: String) -> Boolean,
    /** Queues the deletion of [clientId] for the next push. Removes any queued save first. */
    enqueueDelete: (clientId: String) -> Unit,
) {
    for (id in pendingTombstoneIds()) {
        if (!isDeleteQueued(id)) enqueueDelete(id)
    }
}

/** Walk every page of pending USER tombstones and enqueue a delete for each one not queued yet.
 *
 * Page 1 is [pendingIds](limit); while a page returns exactly [limit] ids, the next page is
 * [pendingIdsAfter](last id of the page, [limit]); the walk stops when a page returns fewer than
 * [limit] ids. It is bounded by [maxPages] pages (a safety cap: 1000 pages at 1000 ids = 1,000,000
 * ids) so a damaged history store cannot make the start-up backfill walk for ever.
 *
 * The loop lives here so it can be tested without Android; [backfillPendingTombstones] is its
 * single-page form.
 */
internal fun backfillAllUserTombstonesChecked(
    /** Page 1: the newest [limit] pending tombstone ids, id descending. */
    pendingIds: (limit: Int) -> List<String>,
    /** The next page: ids below [lastId], id descending. */
    pendingIdsAfter: (lastId: String, limit: Int) -> List<String>,
    /** True when a row with [clientId] is already queued as a delete. A queued save does not count. */
    isDeleteQueued: (clientId: String) -> Boolean,
    /** Queues the deletion of [clientId] for the next push. Removes any queued save first. */
    enqueueDelete: (clientId: String) -> Unit,
    limit: Int = 1000,
    maxPages: Int = 1000,
): BackfillWalk {
    var lastId = ""
    var pages = 0
    var queued = 0
    var complete = false
    while (pages < maxPages) {
        val ids = if (lastId.isEmpty()) pendingIds(limit) else pendingIdsAfter(lastId, limit)
        if (ids.isEmpty()) {
            complete = true
            break
        }
        for (id in ids) {
            if (!isDeleteQueued(id)) {
                enqueueDelete(id)
                queued += 1
            }
        }
        lastId = ids.last()
        pages += 1
        // A short page is the last page: keyset pagination returns at most [limit] ids per page,
        // so fewer than [limit] means there is no page after this one. Stopping here avoids one
        // extra lookup that would come back empty.
        if (ids.size < limit) {
            complete = true
            break
        }
    }
    return BackfillWalk(queued = queued, complete = complete)
}

/**
 * What a paged backfill walk did: [queued] deletes were queued, and [complete] is true only when the walk
 * reached the end of the tombstones (an empty page or a short one). It is false when the page cap stopped the
 * walk first, even if that happened to be the last page, because the walk cannot tell.
 */
internal data class BackfillWalk(val queued: Int, val complete: Boolean)

/** The same walk, answering only how many deletes it queued. */
internal fun backfillAllUserTombstones(
    pendingIds: (limit: Int) -> List<String>,
    pendingIdsAfter: (lastId: String, limit: Int) -> List<String>,
    isDeleteQueued: (clientId: String) -> Boolean,
    enqueueDelete: (clientId: String) -> Unit,
    limit: Int = 1000,
    maxPages: Int = 1000,
): Int = backfillAllUserTombstonesChecked(pendingIds, pendingIdsAfter, isDeleteQueued, enqueueDelete, limit, maxPages).queued

/** Runs [push] on its own worker thread and returns once it finishes or [timeoutMillis] elapse.
 *
 * On timeout the push is abandoned: the push keeps running on its own worker and the caller moves
 * on, so a hanging push never blocks the caller — sign-out included. Every outcome is swallowed;
 * the caller learns only that the push finished or that the bound elapsed.
 */
fun boundedPush(push: () -> Unit, timeoutMillis: Long = 5_000) {
    // Each push runs on its OWN daemon thread, never the JVM common pool: a hung push must not
    // starve later sign-out pushes. The thread is created in host/AppThreads.kt, the one file that
    // may name a thread, and is abandoned once the bound elapses.
    runBoundedOnOwnThread("breaker-signout-push", timeoutMillis, push)
}

/** Finish an interrupted sign-out clear: when the clear marker is set, clear the sync queue, the
 * history tombstones and the pull cursor, then remove the marker. Returns true when it did the
 * clear — the caller must then skip the backfill, so no delete is re-queued over a tombstone
 * being cleared. When the marker is not set it does nothing and returns false.
 *
 * A crash in the middle leaves the marker set: the next start finishes the clear.
 */
fun finishClearIfNeeded(
    clearMarkerIsSet: () -> Boolean,
    clearQueue: () -> Unit,
    clearTombstones: () -> Unit,
    clearPullCursor: () -> Unit,
    clearMarker: () -> Unit,
): Boolean {
    if (!clearMarkerIsSet()) return false
    clearQueue()
    clearTombstones()
    clearPullCursor()
    clearMarker()
    return true
}

/** Runs the backfill only when sync is allowed; with sync off no queue file is touched at all. */
internal fun runBackfillWhenAllowed(syncOn: Boolean, backfill: () -> Unit): Boolean {
    if (!syncOn) return false
    backfill()
    return true
}
