package dev.breaker.server.syncapi.db

internal object SchemaV9 {
    // Version 9 adds the reset-pending mark of an account and the table of reset codes.
    //
    // accounts.reset_pending is added with ADD COLUMN and is 0 for every existing row. The table is
    // not rebuilt, because other tables hold foreign keys to accounts that would cascade, and no
    // existing row is touched. A pending account has had its verifier replaced by random bytes and
    // its keys cleared, and waits for a reset to be completed.
    //
    // reset_codes holds one row per account, keyed by the account, so a new code replaces the old one.
    // The row keeps the SHA-256 of the code, never the code text. A code is dead AT expires_at_ms:
    // live means expires_at_ms > now. The row goes with the account (ON DELETE CASCADE), on use, and
    // on expiry. The typeof test is there because length() counts characters of a TEXT value.
    private val addResetPending: String =
        "ALTER TABLE accounts ADD COLUMN reset_pending INTEGER NOT NULL DEFAULT 0 " +
            "CHECK (reset_pending IN (0, 1))"

    private val createResetCodes: String = """
        CREATE TABLE reset_codes (
          account_id     INTEGER PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
          code_hash      BLOB    NOT NULL CHECK (typeof(code_hash) = 'blob' AND length(code_hash) = 32),
          issued_at_ms   INTEGER NOT NULL CHECK (issued_at_ms >= 0),
          expires_at_ms  INTEGER NOT NULL CHECK (expires_at_ms > issued_at_ms)
        )
    """.trimIndent()

    private val createResetCodesExpires: String =
        "CREATE INDEX reset_codes_expires ON reset_codes (expires_at_ms)"

    val MIGRATION: Migration = Migration(
        9,
        listOf(
            addResetPending,
            createResetCodes,
            createResetCodesExpires,
        ),
    )
}
