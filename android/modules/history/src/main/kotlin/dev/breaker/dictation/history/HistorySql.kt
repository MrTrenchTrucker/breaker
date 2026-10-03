package dev.breaker.dictation.history

/**
 * The module's SQL, as text.
 *
 * The statements live here, free of Android imports, so that a plain JVM test
 * can read them and run them on a real SQLite. The difference between a
 * correct retention purge and one that quietly deletes a transcription from
 * inside the window is a single character in a `WHERE` clause.
 *
 * Not every statement is here. The adapter deletes through
 * `SQLiteDatabase.delete(table, where, args)`, which builds the `DELETE`
 * itself, so for the retention deletes what lives here is the table name and
 * the `WHERE` predicate, and the adapter passes those constants to it. The
 * delete by id is the one piece of SQL the adapter spells inline: its
 * `id = ?` predicate is not a constant here.
 *
 * The boundary predicates are written once, in [RetentionBoundary], and named
 * by the two constants below. The selection that decides which rows get a
 * tombstone is built from the first, and the adapter passes that same constant
 * to the delete that removes them, so the two cannot disagree: there is only
 * one boundary, and it is a strict `<`. The adapter cannot run on a JVM, so the
 * tests read its three delete calls as text (`AdapterStatements`, in the test
 * folder), check which table and predicate each one passes, and run them on
 * real SQLite.
 */
internal object HistorySql {

    const val TABLE_TRANSCRIPTIONS: String = "transcriptions"
    const val TABLE_TOMBSTONES: String = "tombstones"

    /** Bumped only when a statement below changes in a way that needs a migration. */
    const val SCHEMA_VERSION: Int = 1

    /** The name the database is opened under, inside the app-private directory. */
    const val DATABASE_NAME: String = "breaker_history.db"

    /**
     * The retention boundary: created **strictly before** the cutoff.
     *
     * A row created at exactly the cutoff is inside the window and survives.
     * Written with `<` and not `<=`; a `<=` here purges a transcription that
     * was still in date, and nothing downstream would notice — the row would
     * simply be gone.
     *
     * The text is [RetentionBoundary]'s, not this file's: the in-memory database
     * the store is tested against applies the same rule in Kotlin, and a rule
     * stated in two places is a rule that will eventually be true in one of them.
     */
    const val CREATED_AT_STRICTLY_BEFORE_WHERE: String = RetentionBoundary.CREATED_AT_PREDICATE

    /** The same convention for tombstones, which expire on their own clock. */
    const val DELETED_AT_STRICTLY_BEFORE_WHERE: String = RetentionBoundary.DELETED_AT_PREDICATE

    val CREATE_TRANSCRIPTIONS: String = """
        CREATE TABLE IF NOT EXISTS $TABLE_TRANSCRIPTIONS (
            id TEXT PRIMARY KEY NOT NULL,
            text TEXT NOT NULL,
            source TEXT NOT NULL,
            model TEXT NOT NULL,
            duration_ms INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            audio_path TEXT
        )
    """.trimIndent()

    /**
     * The retention window is swept by creation time, so that is what the index
     * is on. Without it, every cleanup is a full table scan.
     */
    val CREATE_TRANSCRIPTIONS_CREATED_AT_INDEX: String =
        "CREATE INDEX IF NOT EXISTS idx_transcriptions_created_at ON $TABLE_TRANSCRIPTIONS (created_at)"

    val CREATE_TOMBSTONES: String = """
        CREATE TABLE IF NOT EXISTS $TABLE_TOMBSTONES (
            id TEXT PRIMARY KEY NOT NULL,
            deleted_at INTEGER NOT NULL,
            reason TEXT NOT NULL
        )
    """.trimIndent()

    val CREATE_TOMBSTONES_DELETED_AT_INDEX: String =
        "CREATE INDEX IF NOT EXISTS idx_tombstones_deleted_at ON $TABLE_TOMBSTONES (deleted_at)"

    val CREATE_ALL: List<String> = listOf(
        CREATE_TRANSCRIPTIONS,
        CREATE_TRANSCRIPTIONS_CREATED_AT_INDEX,
        CREATE_TOMBSTONES,
        CREATE_TOMBSTONES_DELETED_AT_INDEX,
    )

    val TRANSCRIPTION_COLUMNS: String = "id, text, source, model, duration_ms, created_at, audio_path"

    /**
     * `INSERT OR REPLACE`, not `INSERT`: the same id saved twice is one row
     * updated, never a duplicate. A retry of a dictation that already landed
     * must not give the user two copies of the same sentence.
     */
    val INSERT_OR_REPLACE: String = """
        INSERT OR REPLACE INTO $TABLE_TRANSCRIPTIONS
            ($TRANSCRIPTION_COLUMNS)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()

    /**
     * Newest first. The `id` tiebreak is not decoration: two dictations in the
     * same millisecond must not swap places between two `list()` calls, or the
     * list the user is looking at is not the list they tapped.
     */
    val SELECT_NEWEST: String = """
        SELECT $TRANSCRIPTION_COLUMNS FROM $TABLE_TRANSCRIPTIONS
        ORDER BY created_at DESC, id DESC
        LIMIT ?
    """.trimIndent()

    /** Oldest first, so a purge tombstones in the order the rows aged out. */
    val SELECT_IDS_CREATED_BEFORE: String =
        "SELECT id FROM $TABLE_TRANSCRIPTIONS WHERE $CREATED_AT_STRICTLY_BEFORE_WHERE " +
            "ORDER BY created_at ASC, id ASC"

    val INSERT_OR_REPLACE_TOMBSTONE: String = """
        INSERT OR REPLACE INTO $TABLE_TOMBSTONES (id, deleted_at, reason)
        VALUES (?, ?, ?)
    """.trimIndent()

    val SELECT_NEWEST_TOMBSTONES: String = """
        SELECT id, deleted_at, reason FROM $TABLE_TOMBSTONES
        ORDER BY deleted_at DESC, id DESC
        LIMIT ?
    """.trimIndent()

    val COUNT_TRANSCRIPTIONS: String = "SELECT COUNT(*) FROM $TABLE_TRANSCRIPTIONS"
}
