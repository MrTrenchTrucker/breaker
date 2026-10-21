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

    /** How many rows are queued. */
    fun count(): Int

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
