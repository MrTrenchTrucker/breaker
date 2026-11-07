package dev.breaker.dictation.sync

/**
 * The storage the queue sits on.
 *
 * [QueuedSyncService] holds the rules: what is sent, in what order, and when a
 * row is removed. This interface holds only the rows. A row is returned as it is
 * stored, and no method here decides whether a row can be pushed.
 */
internal interface SyncQueueDatabase {

    /**
     * Add one row under [clientId].
     *
     * [source] is the wire text of the source, "local" or "server". [createdAtMs]
     * is the epoch millisecond at which the transcription was made.
     *
     * True when a row was added. False when the id was already queued: the first
     * copy stays and the new one is dropped.
     */
    fun insertIfAbsent(
        clientId: String,
        text: String,
        source: String,
        model: String,
        durationMs: Long,
        createdAtMs: Long,
    ): Boolean

    /** The row with the lowest seq, or null when the queue is empty. */
    fun head(): QueueEntry?

    /** The row with the lowest seq that is greater than [seq], or null when there is none. */
    fun nextAfter(seq: Long): QueueEntry?

    /** The highest seq queued now, or 0 when the queue is empty. */
    fun tailSeq(): Long

    /** Remove the row queued under [clientId]. True when a row was removed. */
    fun remove(clientId: String): Boolean

    /** How many rows are queued and not parked, waiting to be sent. Parked rows are excluded. */
    fun count(): Int

    /** How many rows are parked (marked as permanently refused). */
    fun parkedCount(): Int

    /**
     * Marks the row queued under [clientId] as parked, storing [reason]. The row stays in the
     * store, is never retried, and is skipped by head and nextAfter. True when a row was parked.
     */
    fun parkRow(clientId: String, reason: String): Boolean

    /**
     * Resaves a parked row queued under [clientId]: clears its parked reason and refreshes its
     * values, keeping its seq so it goes through the normal drain again. True when a parked row
     * was found and resaved. A live (not parked) row is left unchanged and returns false.
     */
    fun resaveRow(
        clientId: String,
        text: String,
        source: String,
        model: String,
        durationMs: Long,
        createdAtMs: Long,
    ): Boolean

    /**
     * Reads the parked reason text of the row queued under [clientId], or null when it is not parked
     * or there is no such row.
     */
    fun parkedReason(clientId: String): String?

    /** True when a row with [clientId] is in the queue table.
     * Parked rows count: a parked row is still queued. */
    fun isQueued(clientId: String): Boolean

    /** The client ids of every queue row, parked or not, oldest first (by seq).
     * No text is read, nothing is changed. */
    fun queuedIds(): List<String>

    /**
     * Reads the stored cursor text of the last complete pull, or null when there is none.
     * The text is returned as stored: it is opaque and is never judged or parsed here.
     */
    fun pullCursor(): String?

    /**
     * Stores the cursor text, replacing the one that is there, so the store always holds
     * at most one. The text is stored as it is given, byte for byte.
     */
    fun savePullCursor(cursor: String)

    /**
     * Removes the stored cursor text, or none when there is none. It touches only the
     * cursor table, never the queue.
     */
    fun clearPullCursor()

    /**
     * Run [block] as one all-or-nothing unit.
     *
     * When [block] returns, its changes are kept and its result is returned.
     * When it throws, none of its changes are kept and the exception reaches the
     * caller unchanged.
     */
    fun <T> transaction(block: () -> T): T

    /** Release the underlying handle. */
    fun close()
}
