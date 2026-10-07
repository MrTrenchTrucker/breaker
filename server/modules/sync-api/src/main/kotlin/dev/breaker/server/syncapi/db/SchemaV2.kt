package dev.breaker.server.syncapi.db

internal object SchemaV2 {
    // The UNIQUE on token_hash is also the lookup index, so resolving a presented
    // token needs no extra index. A revoked row is kept (a soft revoke) so its id
    // stays unique and revoking it again changes nothing.
    //
    // expires_at_ms is nullable for both kinds and no constraint forbids a value on
    // a session token, so adding session expiry later needs no schema change; the
    // Kotlin code never writes one for a session. A NULL means "never expires".
    //
    // The CHECK constraints repeat the rules the Kotlin code enforces, as in version
    // 1: a code path that forgets a rule still cannot store a row that breaks it.
    private val createTokens: String = """
        CREATE TABLE tokens (
          id             INTEGER PRIMARY KEY AUTOINCREMENT,
          account_id     INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          kind           TEXT    NOT NULL CHECK (kind IN ('session','agent')),
          scope          TEXT    CHECK (scope IN ('transcribe','admin')),
          label          TEXT    CHECK (length(label) BETWEEN 1 AND 64),
          token_hash     BLOB    NOT NULL UNIQUE CHECK (length(token_hash) = 32),
          created_at_ms  INTEGER NOT NULL,
          expires_at_ms  INTEGER,
          revoked_at_ms  INTEGER,
          CHECK ((kind = 'session' AND scope IS NULL AND label IS NULL)
              OR (kind = 'agent'   AND scope IS NOT NULL AND label IS NOT NULL))
        )
    """.trimIndent()

    private val createTokensIndex: String =
        "CREATE INDEX tokens_account_kind ON tokens (account_id, kind)"

    val MIGRATION: Migration = Migration(2, listOf(createTokens, createTokensIndex))
}
