package dev.breaker.server.syncapi.db

internal object SchemaV3 {
    // Version 3 adds the account's current key version and the transcriptions table.
    //
    // accounts.key_version is the account's current DEK generation. Nothing in this version
    // changes it after the migration; the key slices will. The DEFAULT 0 gives every account
    // that exists before this version the value 0.
    //
    // A transcription is one row per (account, client id). The store decides what a push does
    // from the pair (client id, key version). The row id is AUTOINCREMENT, so it is never
    // reused, and the list order is the id order: a replace keeps the id and so keeps the place.
    //
    // The CHECK constraints keep a bypass of the Kotlin code from storing a bad row, as in versions 1 and 2.
    private val addKeyVersion: String =
        "ALTER TABLE accounts ADD COLUMN key_version INTEGER NOT NULL DEFAULT 0 CHECK (key_version >= 0)"

    private val createTranscriptions: String = """
        CREATE TABLE transcriptions (
          id             INTEGER PRIMARY KEY AUTOINCREMENT,
          account_id     INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          client_id      TEXT    NOT NULL CHECK (length(client_id) = 36),
          key_version    INTEGER NOT NULL CHECK (key_version >= 0),
          encrypted      TEXT    NOT NULL CHECK (length(encrypted) >= 1),
          stored_at_ms   INTEGER NOT NULL,
          UNIQUE (account_id, client_id)
        )
    """.trimIndent()

    val MIGRATION: Migration = Migration(3, listOf(addKeyVersion, createTranscriptions))
}
