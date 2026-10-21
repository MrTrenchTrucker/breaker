package dev.breaker.dictation.sync

/**
 * The module's SQL, as text.
 *
 * The statements live here, free of Android imports, so that a plain JVM test
 * can read them and run them on a real SQLite. [SqliteSyncQueueDatabase] uses
 * these constants and spells no SQL of its own.
 */
internal object SyncQueueSql {

    const val TABLE_QUEUE: String = "sync_queue"

    /** Bumped only when a statement below changes in a way that needs a migration. */
    const val SCHEMA_VERSION: Int = 1

    /** The name the database is opened under, inside the app-private directory. */
    const val DATABASE_NAME: String = "breaker_sync.db"

    /**
     * The queue table.
     *
     * `AUTOINCREMENT`: a seq is never reused after its row is removed, so a later
     * row can never take the place of an earlier one in the order.
     * `UNIQUE` on `client_id`: one row per transcription id.
     * `source` holds the wire text, "local" or "server". The queue checks it
     * before it sends the row. `created_at` is the epoch millisecond at which the
     * transcription was made, in UTC.
     */
    const val CREATE_QUEUE: String =
        "CREATE TABLE IF NOT EXISTS $TABLE_QUEUE (" +
            "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "client_id TEXT NOT NULL UNIQUE, " +
            "text TEXT NOT NULL, " +
            "source TEXT NOT NULL, " +
            "model TEXT NOT NULL, " +
            "duration_ms INTEGER NOT NULL, " +
            "created_at INTEGER NOT NULL)"

    /** Every statement that creates the schema, in the order it runs. */
    val CREATE_ALL: List<String> = listOf(CREATE_QUEUE)

    /** The columns of a queue row, in the order the SELECT statements return them. */
    const val QUEUE_COLUMNS: String = "seq, client_id, text, source, model, duration_ms, created_at"

    /**
     * `INSERT OR IGNORE`: with the `UNIQUE` client id, the same id queued twice
     * leaves the first copy and drops the second. The bind order is client_id,
     * text, source, model, duration_ms, created_at. seq is assigned by the table.
     */
    const val INSERT_IF_ABSENT: String =
        "INSERT OR IGNORE INTO $TABLE_QUEUE " +
            "(client_id, text, source, model, duration_ms, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)"

    /**
     * The oldest row by seq, so the queue drains in the order it was filled.
     * It is not ordered by `created_at`, which can go backwards.
     */
    const val SELECT_HEAD: String =
        "SELECT $QUEUE_COLUMNS FROM $TABLE_QUEUE ORDER BY seq ASC LIMIT 1"

    /**
     * The oldest row whose seq is greater than the bound seq, or none. The drain
     * walks the queue with it, so a skipped row stays where it is and the walk
     * moves on past it. The one bind is the seq of the row just handled, passed as
     * text because the Android rawQuery takes only strings. The CAST states that the
     * comparison is numeric (9 < 10); the INTEGER column already compares a bound
     * text value numerically, so the CAST is explicit rather than needed.
     */
    const val SELECT_NEXT_AFTER: String =
        "SELECT $QUEUE_COLUMNS FROM $TABLE_QUEUE WHERE seq > CAST(? AS INTEGER) ORDER BY seq ASC LIMIT 1"

    /**
     * The highest seq queued now, or 0 when the queue is empty. A drain reads it
     * once, after the probe answers and before its first row, and walks only the
     * rows at or below it.
     */
    const val SELECT_TAIL_SEQ: String = "SELECT COALESCE(MAX(seq), 0) FROM $TABLE_QUEUE"

    /** Removes the one row queued under a client id, or none when there is none. */
    const val DELETE_BY_CLIENT_ID: String = "DELETE FROM $TABLE_QUEUE WHERE client_id = ?"

    /** How many rows are queued. */
    const val COUNT_QUEUE: String = "SELECT COUNT(*) FROM $TABLE_QUEUE"
}
