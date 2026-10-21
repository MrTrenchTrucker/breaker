package dev.breaker.dictation.sync

/**
 * A list-backed [SyncQueueDatabase] for testing the queue's rules on a plain JVM.
 *
 * It is a test double, not a second implementation of the queue. It keeps the properties the
 * service relies on: the entry with the lowest seq is the head, a seq is never reused after its
 * row is removed, the same client id is queued once and the first copy stays, and a transaction
 * whose block throws puts every row back as it was. A rolled-back insert still uses up its seq.
 *
 * It also records what a test needs to see: whether each insert and remove ran inside a
 * transaction, how many transactions committed, and how often each call was made. A failure can
 * be planned for the next call of insert, remove, head or count. An insert or remove can be told
 * to write first and then fail, so only a rollback can take the change back.
 *
 * The head and next-entry calls share one read guard: a drain that never ends would read the queue
 * without bound, so the 501st read throws an [AssertionError] and the test fails instead of hanging.
 */
internal class InMemorySyncQueueDatabase(private val log: CallLog = CallLog()) : SyncQueueDatabase {

    private class PlannedWrite(val cause: RuntimeException, val afterWriting: Boolean)

    private val entries = ArrayList<QueueEntry>()
    private var nextSeq = 1L
    private var reads = 0
    private var plannedInsert: PlannedWrite? = null
    private var plannedRemove: PlannedWrite? = null
    private var plannedHead: RuntimeException? = null
    private var plannedCount: RuntimeException? = null

    /** True while a transaction is open. */
    var insideTransaction: Boolean = false
        private set

    /** How many outermost transactions have committed. */
    var commits: Int = 0
        private set

    /** How many outermost transactions have been rolled back. */
    var rollbacks: Int = 0
        private set

    /** For each insert call, whether it ran inside a transaction. */
    val insertsInsideTransaction = ArrayList<Boolean>()

    /** For each remove call, whether it ran inside a transaction. */
    val removesInsideTransaction = ArrayList<Boolean>()

    /** How many times remove was called. */
    var removeCalls: Int = 0
        private set

    /** How many times head was called. */
    var headCalls: Int = 0
        private set

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    /** Makes the next insert fail with [cause]; with [afterWriting] the row is stored first. */
    fun failNextInsert(cause: RuntimeException, afterWriting: Boolean = true) {
        plannedInsert = PlannedWrite(cause, afterWriting)
    }

    /** Makes the next remove fail with [cause]; with [afterWriting] the row is deleted first. */
    fun failNextRemove(cause: RuntimeException, afterWriting: Boolean = true) {
        plannedRemove = PlannedWrite(cause, afterWriting)
    }

    /** Makes the next head call fail with [cause]. */
    fun failNextHead(cause: RuntimeException) {
        plannedHead = cause
    }

    /** Makes the next count call fail with [cause]. */
    fun failNextCount(cause: RuntimeException) {
        plannedCount = cause
    }

    /** The queued entries in drain order, as stored. */
    fun queued(): List<QueueEntry> = entries.toList()

    /** The client ids of the queued entries in drain order. */
    fun queuedIds(): List<String> = entries.map { it.clientId }

    override fun insertIfAbsent(
        clientId: String,
        text: String,
        source: String,
        model: String,
        durationMs: Long,
        createdAtMs: Long,
    ): Boolean {
        log.add("insert")
        insertsInsideTransaction.add(insideTransaction)
        val planned = plannedInsert
        plannedInsert = null
        if (planned != null && !planned.afterWriting) throw planned.cause
        val added = entries.none { it.clientId == clientId }
        if (added) entries.add(QueueEntry(nextSeq++, clientId, text, source, model, durationMs, createdAtMs))
        if (planned != null) throw planned.cause
        return added
    }

    override fun head(): QueueEntry? {
        log.add("head")
        headCalls++
        countRead()
        val cause = plannedHead
        plannedHead = null
        if (cause != null) throw cause
        return entries.firstOrNull()
    }

    override fun nextAfter(seq: Long): QueueEntry? {
        log.add("next")
        countRead()
        return entries.firstOrNull { it.seq > seq }
    }

    override fun tailSeq(): Long = entries.maxOfOrNull { it.seq } ?: 0L

    override fun remove(clientId: String): Boolean {
        log.add("remove")
        removeCalls++
        removesInsideTransaction.add(insideTransaction)
        val planned = plannedRemove
        plannedRemove = null
        if (planned != null && !planned.afterWriting) throw planned.cause
        val before = entries.size
        entries.removeAll { it.clientId == clientId }
        val removed = entries.size < before
        if (planned != null) throw planned.cause
        return removed
    }

    override fun count(): Int {
        val cause = plannedCount
        plannedCount = null
        if (cause != null) throw cause
        return entries.size
    }

    override fun <T> transaction(block: () -> T): T {
        if (insideTransaction) return block() // nesting joins the outer transaction
        val before = ArrayList<QueueEntry>(entries)
        insideTransaction = true
        try {
            val result = block()
            insideTransaction = false
            commits++
            return result
        } catch (failure: Throwable) {
            entries.clear()
            entries.addAll(before)
            insideTransaction = false
            rollbacks++
            throw failure
        }
    }

    override fun close() {
        closed = true
    }

    private fun countRead() {
        reads++
        if (reads > MAX_READS) {
            throw AssertionError(
                "android_sync: the drain is not making progress: the queue was read more than $MAX_READS times",
            )
        }
    }

    private companion object {
        const val MAX_READS = 500
    }
}
