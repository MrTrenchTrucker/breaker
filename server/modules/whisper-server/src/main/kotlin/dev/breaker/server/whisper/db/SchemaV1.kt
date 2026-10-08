package dev.breaker.server.whisper.db

internal object SchemaV1 {
    // AUTOINCREMENT so an id is never reused: id order is then creation order,
    // which is the FIFO key.
    //
    // The audio column is last on purpose. SQLite stores columns in order, and a
    // large value pushes the columns after it onto overflow pages, so the small
    // columns every poll reads must come before it.
    //
    // The CHECK on audio makes "audio exists only while the job is queued or
    // processing" a property of the schema (ADR-010): a finished job cannot keep
    // audio even through a bug. The result is cleared by a later fetch or purge
    // but never exists before the job is done. Only soft states are used: nothing
    // is deleted from this table in this version.
    private val createJobs: String = """
        CREATE TABLE jobs (
          id                INTEGER PRIMARY KEY AUTOINCREMENT,
          owner_account_id  INTEGER NOT NULL,
          status            TEXT    NOT NULL CHECK (status IN ('queued','processing','done','failed')),
          attempts          INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
          error             TEXT,
          result            TEXT,
          created_at_ms     INTEGER NOT NULL,
          started_at_ms     INTEGER,
          finished_at_ms    INTEGER,
          audio             BLOB,
          CHECK ((status IN ('queued','processing')) = (audio IS NOT NULL)),
          CHECK (audio IS NULL OR length(audio) > 0),
          CHECK (result IS NULL OR status = 'done'),
          CHECK (error IS NULL OR status = 'failed'),
          CHECK (status <> 'failed' OR error IS NOT NULL),
          CHECK ((status IN ('done','failed')) = (finished_at_ms IS NOT NULL)),
          CHECK (status <> 'queued' OR started_at_ms IS NULL),
          CHECK (status <> 'processing' OR started_at_ms IS NOT NULL)
        )
    """.trimIndent()

    private val createIndex: String = "CREATE INDEX jobs_status_id ON jobs (status, id)"

    val MIGRATION: Migration = Migration(1, listOf(createJobs, createIndex))
}
