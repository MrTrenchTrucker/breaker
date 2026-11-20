package dev.breaker.dictation.sync

/** The pending-save markers, paged: page 1 is [after] = null. Each marker carries its run token. */
fun QueuedSyncService.pendingSaveIds(limit: Int, after: String?): List<PendingSaveMarker> =
    database.pendingSaveIds(limit, after)

/** Removes the pending-save marker for [clientId]. True when a marker was removed. */
fun QueuedSyncService.clearPendingSave(clientId: String): Boolean =
    database.clearPendingSave(clientId)

/** Writes a marker for [clientId] stamped with [runToken] (insert-if-absent). True when added. */
fun QueuedSyncService.markPendingSave(clientId: String, runToken: Long): Boolean =
    database.markPendingSave(clientId, runToken)
