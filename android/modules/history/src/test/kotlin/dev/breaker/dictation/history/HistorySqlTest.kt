package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SQL text itself.
 *
 * The store's rules are tested against an in-memory database, which proves what
 * it *asks* for. These tests pin the statements that carry those asks as text:
 * the retention boundary is decided by the `<` in a `WHERE` clause and nothing
 * else — a rule that is right in Kotlin and wrong in the string it compiles to
 * still deletes the user's transcription. Text is all this file checks; the
 * statements are run on a real SQLite engine by `HistorySqlOnSqliteTest`.
 */
class HistorySqlTest {

    @Test
    fun `the retention boundary is a strict less-than`() {
        assertEquals("created_at < ?", HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE)
    }

    @Test
    fun `the purge deletes strictly before the cutoff and not the boundary row`() {
        // The character-level claim. A `<=` here purges a transcription that is
        // still in date, and the store's own boundary test would never see it,
        // because the store asks for "before the cutoff" and the SQL decides
        // what that means.
        val purgeWhere = AdapterStatements.deleteCreatedBefore.where
        assertTrue(purgeWhere.contains("created_at < ?"))
        assertFalse(
            "A `<=` in the purge would delete a transcription created exactly at the cutoff",
            purgeWhere.contains("<="),
        )
    }

    @Test
    fun `the rows selected for a tombstone and the rows deleted use one boundary`() {
        // They are built from the same constant, so a row can never be
        // tombstoned without being deleted, or deleted without being
        // tombstoned. Either way the server and the phone disagree.
        assertTrue(HistorySql.SELECT_IDS_CREATED_BEFORE.contains(HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE))
        assertTrue(HistorySql.SELECT_IDS_CREATED_BEFORE.contains(AdapterStatements.deleteCreatedBefore.where))
    }

    @Test
    fun `the tombstone sweep uses the same strict convention`() {
        assertEquals("deleted_at < ?", HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE)
        assertFalse(AdapterStatements.deleteTombstonesRecordedBefore.where.contains("<="))
    }

    @Test
    fun `saving is a replace, not a plain insert`() {
        // A re-sent dictation under the same id must update one row, not add a
        // second copy of the same sentence.
        assertTrue(HistorySql.INSERT_OR_REPLACE.startsWith("INSERT OR REPLACE"))
        assertTrue(HistorySql.INSERT_OR_REPLACE.contains(HistorySql.TABLE_TRANSCRIPTIONS))
    }

    @Test
    fun `a save binds every column, in the order the columns are declared`() {
        val boundParameters = HistorySql.INSERT_OR_REPLACE.count { it == '?' }
        assertEquals(
            "Every declared column must have a bound parameter, in order",
            HistorySql.TRANSCRIPTION_COLUMNS.split(",").size,
            boundParameters,
        )
        // The order matters: a values list built in a different order than the
        // column list writes a server transcription's text into the model
        // column, and the row still looks plausible.
        val declared = HistorySql.TRANSCRIPTION_COLUMNS.split(",").map { it.trim() }
        assertEquals(
            listOf("id", "text", "source", "model", "duration_ms", "created_at", "audio_path"),
            declared,
        )
    }

    @Test
    fun `history is listed newest first with a stable tiebreak`() {
        assertTrue(HistorySql.SELECT_NEWEST.contains("ORDER BY created_at DESC"))
        assertTrue(
            "Without an id tiebreak two rows in the same millisecond swap places between reads",
            HistorySql.SELECT_NEWEST.contains("id DESC"),
        )
        assertTrue(HistorySql.SELECT_NEWEST.contains("LIMIT ?"))
    }

    @Test
    fun `the ids a purge will tombstone are taken oldest first`() {
        assertTrue(HistorySql.SELECT_IDS_CREATED_BEFORE.contains("ORDER BY created_at ASC"))
    }

    @Test
    fun `the schema holds every column the module card names`() {
        for (column in listOf(
            "id", "text", "source", "model", "duration_ms", "created_at", "audio_path",
        )) {
            assertTrue(
                "The transcriptions table is missing '$column'",
                HistorySql.CREATE_TRANSCRIPTIONS.contains(column),
            )
        }
    }

    @Test
    fun `audio is nullable and stays off by default`() {
        // A history row that points at audio the user has already had deleted
        // is worse than no path at all, so the column exists but nothing writes
        // it.
        val audioColumn = HistorySql.CREATE_TRANSCRIPTIONS.lines()
            .first { it.trimStart().startsWith("audio_path") }
            .trim()
        assertEquals("audio_path TEXT", audioColumn)
        assertTrue(
            "audio_path must be nullable, or a row with no audio cannot be written",
            !audioColumn.contains("NOT NULL"),
        )
        assertTrue(TranscriptionRow.of(sampleTranscription()).audioPath == null)
    }

    @Test
    fun `the source column is a text word, not a number`() {
        // A row a human can read is worth more than a compact one, and an
        // unknown value is an error rather than a silent zero.
        assertTrue(HistorySql.CREATE_TRANSCRIPTIONS.contains("source TEXT NOT NULL"))
        assertEquals("local", TranscriptionRow.of(sampleTranscription(source = "local")).source)
        assertEquals("server", TranscriptionRow.of(sampleTranscription(source = "server")).source)
    }

    @Test
    fun `the retention window has an index to sweep`() {
        // Every cleanup is a full table scan without it, and a cleanup that
        // gets slower over time is a cleanup that eventually does not run.
        assertTrue(HistorySql.CREATE_TRANSCRIPTIONS_CREATED_AT_INDEX.contains("created_at"))
        assertTrue(HistorySql.CREATE_ALL.contains(HistorySql.CREATE_TRANSCRIPTIONS_CREATED_AT_INDEX))
    }

    @Test
    fun `every statement is created on first open, in a stable order`() {
        assertEquals(4, HistorySql.CREATE_ALL.size)
        assertTrue(HistorySql.CREATE_ALL.contains(HistorySql.CREATE_TRANSCRIPTIONS))
        assertTrue(HistorySql.CREATE_ALL.contains(HistorySql.CREATE_TOMBSTONES))
        assertTrue(HistorySql.CREATE_ALL.all { it.startsWith("CREATE ") })
    }

    @Test
    fun `creating is idempotent, so reopening an existing database is safe`() {
        assertTrue(HistorySql.CREATE_ALL.all { it.contains("IF NOT EXISTS") })
    }

    @Test
    fun `the tombstone table holds a reason, so a delete knows where it came from`() {
        for (column in listOf("id", "deleted_at", "reason")) {
            assertTrue(HistorySql.CREATE_TOMBSTONES.contains(column))
        }
    }

    @Test
    fun `the database is opened by name in the app-private directory`() {
        // The name is handed to SQLiteOpenHelper, which places the file in the
        // app-private databases folder. No path, no external storage and
        // no content URI appears anywhere in the module's SQL.
        assertEquals("breaker_history.db", HistorySql.DATABASE_NAME)
        assertFalse(HistorySql.DATABASE_NAME.contains("/"))
        assertFalse(HistorySql.DATABASE_NAME.contains(".."))
    }

    @Test
    fun `no statement interpolates a value into the SQL text`() {
        // Everything is a bound parameter. A statement that built its own WHERE
        // clause by concatenation is a statement with a text commit surface.
        val statements = HistorySql.CREATE_ALL + listOf(
            HistorySql.INSERT_OR_REPLACE,
            HistorySql.SELECT_NEWEST,
            HistorySql.SELECT_IDS_CREATED_BEFORE,
            HistorySql.INSERT_OR_REPLACE_TOMBSTONE,
            HistorySql.SELECT_NEWEST_TOMBSTONES,
            HistorySql.COUNT_TRANSCRIPTIONS,
        ) + AdapterStatements.shipped.values.map { it.where }
        for (statement in statements) {
            assertFalse(
                "A statement contains a quoted literal where a bound parameter belongs: $statement",
                statement.contains("'"),
            )
        }
    }

    private fun sampleTranscription(source: String = "local") = Transcription(
        id = "t-1",
        text = "load the hay",
        source = when (source) {
            TranscriptionRow.SOURCE_SERVER -> TranscriptionSource.SERVER
            else -> TranscriptionSource.LOCAL
        },
        model = "small.en",
        durationMs = 1_200,
        createdAt = 1_700_000_000_000,
    )
}
