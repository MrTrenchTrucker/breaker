package dev.breaker.dictation.sync

/**
 * [SyncQueueDatabase] on [DesktopSqlite]: a JVM twin of `SqliteSyncQueueDatabase`, the Android adapter.
 *
 * It exists so the queue's rules can be run down to real SQL, which the in-memory double cannot do. It is
 * not the adapter, and it does not run the adapter's code.
 *
 * **Taken from [SyncQueueSql], as the adapter takes them.** Every statement here is a [SyncQueueSql]
 * constant the adapter names too. This class has no SQL text of its own. The order in which [insertIfAbsent]
 * binds its values is typed out here again, and the real-SQLite tests would show a twin that drifted from
 * the statement. A source is stored and read as the text it is given.
 *
 * **Modelled, and not checked against Android.** The transaction switches autocommit off and back on, where
 * the adapter uses `beginTransaction` and `endTransaction`. The row mapping reads the driver's values, not an
 * Android `Cursor`. A transaction inside a transaction joins the outer one.
 */
internal class JdbcSyncQueueDatabase(private val sqlite: DesktopSqlite) : SyncQueueDatabase {

    override fun insertIfAbsent(
        clientId: String,
        text: String,
        source: String,
        model: String,
        durationMs: Long,
        createdAtMs: Long,
    ): Boolean =
        sqlite.exec(
            SyncQueueSql.INSERT_IF_ABSENT,
            clientId,
            text,
            source,
            model,
            durationMs,
            createdAtMs,
        ) > 0

    override fun head(): QueueEntry? =
        sqlite.query(SyncQueueSql.SELECT_HEAD).firstOrNull()?.let(::toEntry)

    override fun nextAfter(seq: Long): QueueEntry? =
        sqlite.query(SyncQueueSql.SELECT_NEXT_AFTER, seq.toString()).firstOrNull()?.let(::toEntry)

    override fun tailSeq(): Long =
        sqlite.query(SyncQueueSql.SELECT_TAIL_SEQ).single()[0] as Long

    override fun remove(clientId: String): Boolean = sqlite.exec(SyncQueueSql.DELETE_BY_CLIENT_ID, clientId) > 0

    override fun count(): Int = (sqlite.query(SyncQueueSql.COUNT_QUEUE).single()[0] as Long).toInt()

    override fun <T> transaction(block: () -> T): T {
        val connection = sqlite.connection
        if (!connection.autoCommit) return block() // nesting joins the outer transaction
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = sqlite.close()

    private fun toEntry(row: List<Any?>): QueueEntry =
        QueueEntry(
            seq = row[0] as Long,
            clientId = row[1] as String,
            text = row[2] as String,
            source = row[3] as String,
            model = row[4] as String,
            durationMs = row[5] as Long,
            createdAtMs = row[6] as Long,
        )
}

/** The most entries a test helper will take from a queue before it gives up and fails. */
internal const val MAX_DRAIN_STEPS = 50

/**
 * Takes every entry off this queue, oldest first, and returns their client ids in the order taken. It stops
 * after [MAX_DRAIN_STEPS] entries and fails, so a queue that never empties fails the test and cannot hang it.
 */
internal fun SyncQueueDatabase.drainQueuedIds(): List<String> {
    val ids = ArrayList<String>()
    for (step in 0 until MAX_DRAIN_STEPS) {
        val entry = head() ?: return ids
        val id = entry.clientId
        if (!remove(id)) {
            throw AssertionError("android_sync: head returned $id but remove of it found no row")
        }
        ids.add(id)
    }
    throw AssertionError("android_sync: the queue still had entries after $MAX_DRAIN_STEPS removals")
}
