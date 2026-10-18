package dev.breaker.server.syncapi.db

internal object SchemaV1 {
    // The CHECK constraints repeat the rules the Kotlin code enforces. They are a
    // second line of defence: a code path that forgets a rule still cannot store a
    // row that breaks it.
    //
    // GLOB is used for the username allow-list because it is case sensitive and
    // has no end-of-line special case, so a trailing newline is rejected. SQLite's
    // lower() only folds ASCII, which matches the ASCII-only allow-list, so the
    // stored username_lower cannot disagree with the lower-casing done in Kotlin.
    //
    // kdf_version is pinned to 1 because version 1 fixes the algorithm, so no other
    // value can be stored yet.
    private val createAccounts: String = """
        CREATE TABLE accounts (
          id               INTEGER PRIMARY KEY AUTOINCREMENT,
          username         TEXT    NOT NULL CHECK (length(username) BETWEEN 1 AND 64 AND username NOT GLOB '*[^A-Za-z0-9._-]*'),
          username_lower   TEXT    NOT NULL UNIQUE CHECK (username_lower = lower(username)),
          role             TEXT    NOT NULL CHECK (role IN ('admin','user')),
          salt             BLOB    NOT NULL CHECK (length(salt) = 16),
          kdf_memory_kib   INTEGER NOT NULL CHECK (kdf_memory_kib  BETWEEN 65536 AND 262144),
          kdf_iterations   INTEGER NOT NULL CHECK (kdf_iterations  BETWEEN 3 AND 10),
          kdf_parallelism  INTEGER NOT NULL CHECK (kdf_parallelism BETWEEN 1 AND 4),
          kdf_version      INTEGER NOT NULL CHECK (kdf_version = 1),
          verifier_algo    TEXT    NOT NULL,
          verifier_iters   INTEGER NOT NULL CHECK (verifier_iters >= 1),
          verifier_salt    BLOB    NOT NULL CHECK (length(verifier_salt) = 16),
          verifier_hash    BLOB    NOT NULL CHECK (length(verifier_hash) = 32),
          created_at_ms    INTEGER NOT NULL
        )
    """.trimIndent()

    val MIGRATION: Migration = Migration(1, listOf(createAccounts))
}
