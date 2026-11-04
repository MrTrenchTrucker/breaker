package dev.breaker.server.syncapi.db

internal object SchemaV8 {
    // Version 8 adds the key material of an account and two moments. Every column is added with
    // ADD COLUMN and may be NULL. NULL means the account is keyless: it has not stored keys yet.
    // The table is not rebuilt, because other tables hold foreign keys to accounts that would
    // cascade, and no existing row is touched.
    //
    // wrapped_dek and wrapped_box_privkey are 60 bytes each (nonce, 32-byte key, tag) and
    // box_pubkey is a 32-byte public key. prev_wrapped_dek is the previous data key wrapped under
    // the new one, also 60 bytes. rekeyed_at and reset_at are epoch milliseconds, like
    // created_at_ms. Nothing in this version writes prev_wrapped_dek, rekeyed_at or reset_at.
    //
    // Each blob CHECK also tests typeof, because length() counts characters of a TEXT value: a
    // bypass of the Kotlin code cannot store text of the right length in a key column.
    //
    // The three key columns are all NULL or all set. That rule is a second CHECK on the last of
    // them, box_pubkey, because the other two must already exist when it is evaluated.
    private val addWrappedDek: String =
        "ALTER TABLE accounts ADD COLUMN wrapped_dek BLOB " +
            "CHECK (wrapped_dek IS NULL OR (typeof(wrapped_dek) = 'blob' AND length(wrapped_dek) = 60))"

    private val addWrappedBoxPrivkey: String =
        "ALTER TABLE accounts ADD COLUMN wrapped_box_privkey BLOB " +
            "CHECK (wrapped_box_privkey IS NULL OR " +
            "(typeof(wrapped_box_privkey) = 'blob' AND length(wrapped_box_privkey) = 60))"

    private val addBoxPubkey: String =
        "ALTER TABLE accounts ADD COLUMN box_pubkey BLOB " +
            "CHECK (box_pubkey IS NULL OR (typeof(box_pubkey) = 'blob' AND length(box_pubkey) = 32)) " +
            "CHECK ((wrapped_dek IS NULL) = (wrapped_box_privkey IS NULL) " +
            "AND (wrapped_box_privkey IS NULL) = (box_pubkey IS NULL))"

    private val addPrevWrappedDek: String =
        "ALTER TABLE accounts ADD COLUMN prev_wrapped_dek BLOB " +
            "CHECK (prev_wrapped_dek IS NULL OR " +
            "(typeof(prev_wrapped_dek) = 'blob' AND length(prev_wrapped_dek) = 60))"

    private val addRekeyedAt: String =
        "ALTER TABLE accounts ADD COLUMN rekeyed_at INTEGER CHECK (rekeyed_at IS NULL OR rekeyed_at >= 0)"

    private val addResetAt: String =
        "ALTER TABLE accounts ADD COLUMN reset_at INTEGER CHECK (reset_at IS NULL OR reset_at >= 0)"

    val MIGRATION: Migration = Migration(
        8,
        listOf(
            addWrappedDek,
            addWrappedBoxPrivkey,
            addBoxPubkey,
            addPrevWrappedDek,
            addRekeyedAt,
            addResetAt,
        ),
    )
}
