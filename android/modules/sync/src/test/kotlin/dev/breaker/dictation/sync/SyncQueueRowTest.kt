package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.TranscriptionSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What one stored row holds, run on real SQLite.
 *
 * The row is read back as the text it was stored as: a source that is not a wire word comes back unchanged,
 * and a row the service wrote holds the wire word of its source, the epoch milliseconds of its creation and
 * its text, model and duration in their own columns. The rows are read through the JDBC twin, which runs the
 * same statements as the adapter.
 */
class SyncQueueRowTest {

    private val sqlite = DesktopSqlite()
    private val db = JdbcSyncQueueDatabase(sqlite)

    private val CAFE_TEXT = "caf" + Char(0xE9) + " quartz"

    @After
    fun closeDatabase() = sqlite.close()

    private fun service(): QueuedSyncService =
        QueuedSyncService(database = db, uploader = ScriptedUploader(), probe = ScriptedProbe(true))

    private fun headOrFail(): QueueEntry = db.head() ?: throw AssertionError("android_sync: expected an entry at the head")

    /** Writes a row straight into the table, with a source the service never writes. */
    private fun insertRaw(clientId: String, text: String, source: String) {
        val changed = sqlite.exec(
            "INSERT INTO ${SyncQueueSql.TABLE_QUEUE} (client_id, text, source, model, duration_ms, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            clientId, text, source, "small.en", 1_200L, 1_700_000_000_000L,
        )
        assertEquals("android_sync: the raw insert of $clientId must add one row", 1, changed)
    }

    /** A failure here means a stored source the service never writes is converted or dropped on the way out. */
    @Test
    fun `a stored source that is not a wire word is returned as stored and never converted`() {
        insertRaw("bogus-1", "quartz marmalade for bogus-1", "BOGUS")
        assertEquals("android_sync: the stored source must come back as the word it was stored as", "BOGUS", headOrFail().source)
    }

    /** A failure here means a LOCAL transcription is stored under another word than local. */
    @Test
    fun `a local transcription is stored with the source word local`() {
        service().enqueue(aTranscription("a"))
        assertEquals(
            "android_sync: the LOCAL transcription must be stored as the text local",
            listOf(listOf<Any?>("local")),
            sqlite.query("SELECT source FROM sync_queue WHERE client_id = ?", "a"),
        )
    }

    /** A failure here means a SERVER transcription is stored under another word than server. */
    @Test
    fun `a server transcription is stored with the source word server`() {
        service().enqueue(aTranscription("a", source = TranscriptionSource.SERVER))
        assertEquals(
            "android_sync: the SERVER transcription must be stored as the text server",
            listOf(listOf<Any?>("server")),
            sqlite.query("SELECT source FROM sync_queue WHERE client_id = ?", "a"),
        )
    }

    /** A failure here means created_at is stored in another unit or read back as another number. */
    @Test
    fun `created_at is stored as epoch milliseconds and read back equal`() {
        service().enqueue(aTranscription("a", createdAt = 1_700_000_123_456L))
        assertEquals(
            "android_sync: the created_at column must hold the epoch milliseconds",
            listOf(listOf<Any?>(1_700_000_123_456L)),
            sqlite.query("SELECT created_at FROM sync_queue WHERE client_id = ?", "a"),
        )
        assertEquals("android_sync: created_at read back from the head must be equal", 1_700_000_123_456L, headOrFail().createdAtMs)
    }

    /** A failure here means a column holds another field's value, or a non-ASCII text is changed on the way in. */
    @Test
    fun `the stored row holds text model duration and created_at in their columns`() {
        service().enqueue(aTranscription("a", text = CAFE_TEXT, model = "large-v3", durationMs = 4_500L))
        assertEquals(
            "android_sync: the four columns must hold the values that were enqueued",
            listOf(listOf<Any?>(CAFE_TEXT, "large-v3", 4_500L, 1_700_000_000_000L)),
            sqlite.query("SELECT text, model, duration_ms, created_at FROM sync_queue WHERE client_id = ?", "a"),
        )
    }
}
