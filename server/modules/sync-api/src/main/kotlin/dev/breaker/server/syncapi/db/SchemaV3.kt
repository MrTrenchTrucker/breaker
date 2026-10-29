package dev.breaker.server.syncapi.db

internal object SchemaV3 {
    // One row per (account, client id). The UNIQUE pair is the second line of defence
    // behind the store's own existence check: a code path that forgets the check still
    // cannot keep two rows for the same pair.
    //
    // The CHECK constraints repeat the rules the store enforces, as in the earlier
    // versions: a negative key version and an empty text are refused by the database.
    //
    // The foreign key removes an account's transcriptions when the account is deleted.
    // Foreign keys are enabled on every connection.
    //
    // stored_at_ms holds the stored time in milliseconds, always a whole second: the store
    // cuts the clock to the whole second before it writes the row.
    private val createTranscriptions: String = """
        CREATE TABLE transcriptions (
          id            INTEGER PRIMARY KEY AUTOINCREMENT,
          account_id    INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          client_id     TEXT    NOT NULL CHECK (length(client_id) = 36),
          key_version   INTEGER NOT NULL CHECK (key_version >= 0),
          encrypted     TEXT    NOT NULL CHECK (length(encrypted) > 0),
          stored_at_ms  INTEGER NOT NULL,
          UNIQUE (account_id, client_id)
        )
    """.trimIndent()

    // The list query reads one account's rows in stored-time order, so this index covers
    // both the filter and the sort.
    private val createTranscriptionsIndex: String =
        "CREATE INDEX transcriptions_account_stored ON transcriptions (account_id, stored_at_ms, client_id)"

    val MIGRATION: Migration = Migration(3, listOf(createTranscriptions, createTranscriptionsIndex))
}
