package dev.breaker.server.syncapi.db

internal object SchemaV10 {
    // Version 10 adds the pending-sealed store: one row per still-sealed agent-token
    // job result awaiting the owner's unseal and re-upload.
    // Nothing writes the table yet (transport undecided, ADR-018 Phase 19/20); this
    // migration only installs the read store for GET /v1/sync/pending-sealed.
    //
    // sealed_box is TEXT holding the base64 of the crypto_box_seal output exactly as
    // the spec returns it. The store does not judge the content; validation belongs to
    // the future write path.
    // account_id comes from the bearer token's account, never from the request. The row
    // goes with the account (ON DELETE CASCADE), on the same pattern as reset_codes
    // (SchemaV9:21-26). Foreign keys are ON on every connection (SqliteDatabase.kt:71),
    // so the cascade is enforced.
    private val createPendingSealed: String = """
        CREATE TABLE pending_sealed (
          id         TEXT    PRIMARY KEY,
          account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          sealed_box TEXT    NOT NULL,
          created_at TEXT    NOT NULL
        )
    """.trimIndent()

    private val createPendingSealedAccount: String =
        "CREATE INDEX pending_sealed_account ON pending_sealed (account_id)"

    val MIGRATION: Migration = Migration(
        10,
        listOf(
            createPendingSealed,
            createPendingSealedAccount,
        ),
    )
}
