package dev.breaker.server.syncapi.db

internal object SchemaV5 {
    // Version 5 adds change numbers to transcriptions and accounts, plus a per-token
    // last_pulled_change marker. change_no is written on every insert/replace path and
    // backfilled to existing rows; change_seq counts the account's stored row WRITES (the
    // backfill sets it to the row count, but after a replace it counts writes, not rows).
    //
    // Step (d) backfills change_no via a subselect over a numbered derived table: the
    // window function is never inside a SET clause (SQLite rejects it there).
    //
    // Step (e) creates the unique index on (account_id, change_no) so two rows of the
    // same account can never share a number, while different accounts may reuse numbers.

    private val addChangeSeq: String =
        "ALTER TABLE accounts ADD COLUMN change_seq INTEGER NOT NULL DEFAULT 0 CHECK (change_seq >= 0)"

    private val addPurgedThrough: String =
        "ALTER TABLE accounts ADD COLUMN purged_through INTEGER NOT NULL DEFAULT 0 CHECK (purged_through >= 0)"

    private val addChangeNo: String =
        "ALTER TABLE transcriptions ADD COLUMN change_no INTEGER NOT NULL DEFAULT 0"

    private val addDeleted: String =
        "ALTER TABLE transcriptions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1))"

    private val addLastPulledChange: String =
        "ALTER TABLE tokens ADD COLUMN last_pulled_change INTEGER"

    private val backfillChangeNo: String =
        "UPDATE transcriptions SET change_no = (" +
            "SELECT r.n FROM (" +
            "SELECT id, row_number() OVER (PARTITION BY account_id ORDER BY id) AS n " +
            "FROM transcriptions" +
            ") AS r WHERE r.id = transcriptions.id" +
        ")"

    private val backfillChangeSeq: String =
        "UPDATE accounts SET change_seq = (SELECT count(*) FROM transcriptions WHERE account_id = accounts.id)"

    private val createUniqueIndex: String =
        "CREATE UNIQUE INDEX transcriptions_account_change_no ON transcriptions (account_id, change_no)"

    val MIGRATION: Migration = Migration(
        5,
        listOf(
            addChangeSeq,
            addPurgedThrough,
            addChangeNo,
            addDeleted,
            addLastPulledChange,
            backfillChangeNo,
            backfillChangeSeq,
            createUniqueIndex,
        ),
    )
}
