package dev.breaker.dictation.sync

/**
 * The storage the queue sits on. It is also the pull cursor store and the queue probe the
 * pull merge reads: pullCursor/savePullCursor/clearPullCursor and isQueued/queuedIds.
 *
 * [QueuedSyncService] holds the rules: what is sent, in what order, and when a
 * row is removed. This interface holds only the rows. A row is returned as it is
 * stored, and no method here decides whether a row can be pushed.
 */
internal interface SyncQueueDatabase : PullCursorPort, QueueProbe {

    /**
     * Add one row under [clientId].
     *
     * [source] is the wire text of the source, "local" or "server". [createdAtMs]
     * is the epoch millisecond at which the transcription was made. [kind] is the
     * row kind, [SyncQueueSql.KIND_SAVE] or [SyncQueueSql.KIND_DELETE].
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
        kind: String = SyncQueueSql.KIND_SAVE,
    ): Boolean

    /** Insert one delete row under [clientId]: kind [SyncQueueSql.KIND_DELETE], empty text.
     *
     * True when a row was added. False when the id was already queued: the first
     * copy stays and the new one is dropped.
     */
    fun insertDeleteIfAbsent(clientId: String): Boolean

    /** The row with the lowest seq, or null when the queue is empty. */
    fun head(): QueueEntry?

    /** The row with the lowest seq that is greater than [seq], or null when there is none. */
    fun nextAfter(seq: Long): QueueEntry?

    /** The highest seq queued now, or 0 when the queue is empty. */
    fun tailSeq(): Long

    /** Remove the row queued under [clientId]. True when a row was removed. */
    fun remove(clientId: String): Boolean

    /** Remove the row with [seq]: the row this drain itself read, never a row another step queued.
     * True when a row was removed. */
    fun removeSeq(seq: Long): Boolean

    /** Replace a queued DELETE of [clientId] with a save carrying [text], [source], [model],
     * [durationMs] and [createdAtMs]; the row keeps its seq. True when a delete row was replaced.
     * Parked rows are never matched: a parked row's kind is 'save'. */
    fun replaceQueuedDeleteWithSave(
        clientId: String,
        text: String,
        source: String,
        model: String,
        durationMs: Long,
        createdAtMs: Long,
    ): Boolean

    /** True when a delete of [clientId] is queued; a save of the same id is not. */
    fun isDeleteQueued(clientId: String): Boolean

    /** Remove every row from the queue, parked or not. */
    fun clearAll()

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
    override fun isQueued(clientId: String): Boolean

    /** The client ids of every queue row, parked or not, oldest first (by seq).
     * No text is read, nothing is changed. */
    override fun queuedIds(): List<String>

    /**
     * Reads the stored cursor text of the last complete pull, or null when there is none.
     * The text is returned as stored: it is opaque and is never judged or parsed here.
     */
    override fun pullCursor(): String?

    /**
     * Stores the cursor text, replacing the one that is there, so the store always holds
     * at most one. The text is stored as it is given, byte for byte.
     */
    override fun savePullCursor(cursor: String)

    /**
     * Removes the stored cursor text, or none when there is none. It touches only the
     * cursor table, never the queue.
     */
    override fun clearPullCursor()

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
