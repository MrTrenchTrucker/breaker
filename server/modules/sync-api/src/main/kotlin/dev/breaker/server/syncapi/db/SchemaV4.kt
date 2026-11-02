package dev.breaker.server.syncapi.db

internal object SchemaV4 {
    // Version 4 adds the device name of a session and the table of sign-in hand-off codes.
    //
    // tokens.device_name is the name the app gives at sign-in. An ADD COLUMN CHECK may name only
    // its own column, so the rule that only a session row has a device name cannot be a CHECK:
    // it is two triggers, one for an insert and one for an update of device_name or kind. A row
    // that exists before this version has a NULL name and stays valid.
    //
    // signin_handoff holds one row per code, keyed by the SHA-256 of the code, so the code text is
    // never stored. The challenge is the 43-character text the app sent. The package is the sealed
    // key package, 113 bytes, stored as given and never opened. The exchange deletes the row, so
    // no used marker exists. A row is dead AT expires_at_ms: live means expires_at_ms > now.
    // The table is not a token kind, so a live code can never resolve as a Bearer token.
    private val addDeviceName: String =
        "ALTER TABLE tokens ADD COLUMN device_name TEXT " +
            "CHECK (device_name IS NULL OR length(device_name) BETWEEN 1 AND 64)"

    private val deviceNameInsertTrigger: String = """
        CREATE TRIGGER tokens_device_name_insert BEFORE INSERT ON tokens
        WHEN NEW.device_name IS NOT NULL AND NEW.kind <> 'session'
        BEGIN
          SELECT RAISE(ABORT, 'device_name is for session rows only');
        END
    """.trimIndent()

    private val deviceNameUpdateTrigger: String = """
        CREATE TRIGGER tokens_device_name_update BEFORE UPDATE OF device_name, kind ON tokens
        WHEN NEW.device_name IS NOT NULL AND NEW.kind <> 'session'
        BEGIN
          SELECT RAISE(ABORT, 'device_name is for session rows only');
        END
    """.trimIndent()

    private val createSigninHandoff: String = """
        CREATE TABLE signin_handoff (
          code_hash      BLOB    PRIMARY KEY CHECK (length(code_hash) = 32),
          account_id     INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          challenge      TEXT    NOT NULL CHECK (length(challenge) = 43),
          package        BLOB    NOT NULL CHECK (length(package) = 113),
          created_at_ms  INTEGER NOT NULL,
          expires_at_ms  INTEGER NOT NULL
        )
    """.trimIndent()

    private val createSigninHandoffIndex: String =
        "CREATE INDEX signin_handoff_account_created ON signin_handoff (account_id, created_at_ms)"

    val MIGRATION: Migration = Migration(
        4,
        listOf(
            addDeviceName,
            deviceNameInsertTrigger,
            deviceNameUpdateTrigger,
            createSigninHandoff,
            createSigninHandoffIndex,
        ),
    )
}
