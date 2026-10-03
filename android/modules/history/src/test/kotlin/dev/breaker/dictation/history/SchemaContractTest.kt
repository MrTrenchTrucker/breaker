package dev.breaker.dictation.history

import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The schema as a contract, run on a real SQLite ([DesktopSqlite]).
 *
 * [HistorySqlTest] reads the `CREATE` statements as text. These tests open a fresh database and ask the
 * engine what it holds: which tables and indexes exist, which column each index is on, and which columns
 * refuse a NULL. Every refusal test inserts a valid row first, in the same test, and then the same row with
 * one column NULL, so the refusal can only come from that column. The engine's message must name the column.
 *
 * The limit is the one [DesktopSqlite] states: the desktop SQLite in the test-only jar, not the phone's.
 */
class SchemaContractTest {

    private val sqlite = DesktopSqlite()

    @After
    fun closeDatabase() = sqlite.close()

    // ── what a fresh database holds ──────────────────────────────────────

    @Test
    fun `a fresh database holds exactly the two tables the card names`() {
        val tables = sqlite.firstColumn(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        )

        assertEquals(listOf("tombstones", "transcriptions"), tables)
    }

    @Test
    fun `a fresh database holds exactly the two named indexes, each on its own table`() {
        val indexes = sqlite.query(
            "SELECT name, tbl_name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_autoindex%' " +
                "ORDER BY name",
        )

        assertEquals(
            listOf(
                listOf("idx_tombstones_deleted_at", "tombstones"),
                listOf("idx_transcriptions_created_at", "transcriptions"),
            ),
            indexes,
        )
    }

    @Test
    fun `the table names in the module are the words on the database, written out`() {
        // Written as literals on purpose: a database already on a phone holds these names, and a rename
        // in the constants would leave that data behind in a table nothing reads.
        assertEquals("transcriptions", HistorySql.TABLE_TRANSCRIPTIONS)
        assertEquals("tombstones", HistorySql.TABLE_TOMBSTONES)
    }

    @Test
    fun `the schema version is 1`() {
        assertEquals(1, HistorySql.SCHEMA_VERSION)
    }

    // ── the indexes are on the columns the sweeps filter by ──────────────

    @Test
    fun `the created_at index is on created_at and nothing else`() {
        assertEquals(listOf("created_at"), indexColumns("idx_transcriptions_created_at"))
    }

    @Test
    fun `the tombstone index is on deleted_at and nothing else`() {
        assertEquals(listOf("deleted_at"), indexColumns("idx_tombstones_deleted_at"))
    }

    @Test
    fun `the retention select is planned on the created_at index`() {
        val plan = planOf(HistorySql.SELECT_IDS_CREATED_BEFORE)

        assertTrue("plan was: $plan", plan.contains("USING INDEX idx_transcriptions_created_at"))
    }

    @Test
    fun `the tombstone sweep is planned on the deleted_at index`() {
        val shipped = AdapterStatements.deleteTombstonesRecordedBefore
        val plan = planOf("DELETE FROM ${shipped.table} WHERE ${shipped.where}")

        assertTrue("plan was: $plan", plan.contains("USING INDEX idx_tombstones_deleted_at"))
    }

    // ── a NULL is refused where the column says NOT NULL ─────────────────

    @Test
    fun `a transcription with no id is refused`() = assertTranscriptionRefusesNull("id")

    @Test
    fun `a transcription with no text is refused`() = assertTranscriptionRefusesNull("text")

    @Test
    fun `a transcription with no model is refused`() = assertTranscriptionRefusesNull("model")

    @Test
    fun `a transcription with no duration is refused`() = assertTranscriptionRefusesNull("duration_ms")

    @Test
    fun `a transcription with no creation time is refused`() = assertTranscriptionRefusesNull("created_at")

    @Test
    fun `a tombstone with no id is refused`() = assertTombstoneRefusesNull("id")

    @Test
    fun `a tombstone with no reason is refused`() = assertTombstoneRefusesNull("reason")

    // ── helpers ──────────────────────────────────────────────────────────

    private val transcriptionColumns =
        listOf("id", "text", "source", "model", "duration_ms", "created_at", "audio_path")

    private val tombstoneColumns = listOf("id", "deleted_at", "reason")

    private fun validTranscription(id: String): List<Any?> =
        listOf(id, "load the hay", "local", "small.en", 1_200L, 1_775_044_800_000L, null)

    private fun validTombstone(id: String): List<Any?> = listOf(id, 1_775_044_800_000L, "retention")

    private fun assertTranscriptionRefusesNull(column: String) {
        val table = HistorySql.TABLE_TRANSCRIPTIONS
        assertEquals(
            "the control row is stored",
            1,
            sqlite.exec(HistorySql.INSERT_OR_REPLACE, *validTranscription("control").toTypedArray()),
        )
        val broken = validTranscription("null-$column").toMutableList()
        broken[transcriptionColumns.indexOf(column)] = null

        assertRefusedAsNull(table, column) { sqlite.exec(HistorySql.INSERT_OR_REPLACE, *broken.toTypedArray()) }

        assertEquals(listOf<Any?>("control"), sqlite.firstColumn("SELECT id FROM $table"))
    }

    private fun assertTombstoneRefusesNull(column: String) {
        val table = HistorySql.TABLE_TOMBSTONES
        assertEquals(
            "the control tombstone is stored",
            1,
            sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, *validTombstone("control").toTypedArray()),
        )
        val broken = validTombstone("null-$column").toMutableList()
        broken[tombstoneColumns.indexOf(column)] = null

        assertRefusedAsNull(table, column) {
            sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, *broken.toTypedArray())
        }

        assertEquals(listOf<Any?>("control"), sqlite.firstColumn("SELECT id FROM $table"))
    }

    /** [insert] must raise the engine's NOT NULL refusal, and the message must name [table] and [column]. */
    private fun assertRefusedAsNull(table: String, column: String, insert: () -> Int) {
        try {
            insert()
        } catch (refusal: SQLException) {
            assertTrue(
                "the refusal should name $table.$column, but was: ${refusal.message}",
                refusal.message.orEmpty().contains("NOT NULL constraint failed: $table.$column"),
            )
            return
        }
        fail("a NULL $column was stored in $table")
    }

    private fun indexColumns(index: String): List<Any?> =
        sqlite.query("PRAGMA index_info($index)").map { it[2] }

    private fun planOf(sql: String): String =
        sqlite.query("EXPLAIN QUERY PLAN $sql", "1775044800000").joinToString(" | ") { it.last().toString() }
}
