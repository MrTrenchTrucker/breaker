package dev.breaker.dictation.history

import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SQL, run.
 *
 * [HistorySqlTest] reads the statements as text, and the store tests run against
 * an in-memory double, so neither ever executes a `WHERE` clause. These tests do:
 * every statement here runs on a real SQLite engine ([DesktopSqlite]) at the
 * millisecond either side of the retention cutoff, because the difference between
 * a purge that keeps a row created at the cutoff and one that deletes it is one
 * character in a string, and only an engine can read that character.
 *
 * The cutoff is bound as **text**, exactly as the phone's adapter binds it. That
 * is right only if SQLite compares the bare `created_at` column, which has
 * integer affinity, as a number. A test below proves it does, and another proves
 * what happens when it does not: on a column declared as text the same bound
 * cutoff picks the wrong rows.
 *
 * **The deletes are the adapter's.** The delete tests do not type a statement.
 * They run the table and the `WHERE` that [AdapterStatements] reads out of the
 * adapter's own source, so a change to a delete in the adapter changes what runs
 * here, and a change [AdapterStatements] cannot read fails there, loudly.
 *
 * **The limit.** This is the desktop SQLite in the test-only `sqlite-jdbc` jar,
 * not the phone's SQLite, so the SQL stays in the subset the two share. The
 * on-device proof comes with the launch test. `SqliteHistoryDatabase`, the Android
 * adapter, runs in no JVM test, and how `SQLiteDatabase.delete` assembles its
 * statement is modelled by [DesktopSqlite], not checked against Android.
 */
class HistorySqlOnSqliteTest {

    private val sqlite = DesktopSqlite()

    /**
     * 2026-04-01T12:00:00Z as epoch milliseconds: the instant the window closes for a
     * purge at 2026-06-30T12:00:00Z. Written as a number, worked out by hand and
     * checked against Python's `timedelta(days=90)`, so it does not come from the
     * code under test.
     */
    private val cutoff = 1_775_044_800_000L

    @After
    fun closeDatabase() = sqlite.close()

    private fun saveRow(id: String, createdAt: Long, text: String = "load the hay") {
        sqlite.exec(HistorySql.INSERT_OR_REPLACE, id, text, "local", "small.en", 1_200L, createdAt, null)
    }

    private fun saveTombstone(id: String, deletedAt: Long) {
        sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, id, deletedAt, "retention")
    }

    private fun ids(): List<Any?> =
        sqlite.firstColumn("SELECT id FROM ${HistorySql.TABLE_TRANSCRIPTIONS} ORDER BY id")

    private fun tombstoneIds(): List<Any?> =
        sqlite.firstColumn("SELECT id FROM ${HistorySql.TABLE_TOMBSTONES} ORDER BY id")

    /** Runs a delete as the adapter builds it: its table and `WHERE`, read from its source, and [arg] bound as text. */
    private fun runAdapterDelete(statement: AdapterStatements.ShippedDelete, arg: String): Int =
        sqlite.delete(statement.table, statement.where, arg)

    // ── the engine is real, and the literal is right ─────────────────────

    @Test
    fun `the environment probe finds a SQLite engine that answers with a version number`() {
        // A probe, not a gate: any SQLite answers this. It shows which engine the other tests in this
        // class ran on, and it fails only when there is no engine to ask.
        val version = sqlite.firstColumn("SELECT sqlite_version()").single() as String
        assertTrue("sqlite_version() answered '$version'", Regex("""\d+\.\d+\.\d+""").matches(version))
    }

    @Test
    fun `the cutoff literal is noon UTC on 1 April 2026, which is the policy's cutoff for noon on 30 June`() {
        assertEquals(Instant.parse("2026-04-01T12:00:00Z").toEpochMilli(), cutoff)
        assertEquals(RetentionPolicy().cutoff(Instant.parse("2026-06-30T12:00:00Z")).toEpochMilli(), cutoff)
    }

    @Test
    fun `an integer is stored as an integer, so the column compares as a number`() {
        saveRow("a", cutoff)
        assertEquals(
            listOf<Any?>("integer"),
            sqlite.firstColumn("SELECT typeof(created_at) FROM ${HistorySql.TABLE_TRANSCRIPTIONS}"),
        )
    }

    // ── the retention boundary, on rows, at cutoff - 1 / cutoff / cutoff + 1 ──

    @Test
    fun `the retention select takes only rows strictly before the cutoff`() {
        saveRow("after", cutoff + 1)
        saveRow("at", cutoff)
        saveRow("before", cutoff - 1)

        val selected = sqlite.firstColumn(HistorySql.SELECT_IDS_CREATED_BEFORE, cutoff.toString())

        assertEquals(
            "a row at the cutoff is in date; only the millisecond before it is out",
            listOf<Any?>("before"),
            selected,
        )
    }

    @Test
    fun `the retention select takes the doomed rows oldest first`() {
        saveRow("b", cutoff - 5)
        saveRow("a", cutoff - 5)
        saveRow("c", cutoff - 9)

        val selected = sqlite.firstColumn(HistorySql.SELECT_IDS_CREATED_BEFORE, cutoff.toString())

        assertEquals(listOf<Any?>("c", "a", "b"), selected)
    }

    // ── the deletes, as the adapter builds them ──────────────────────────

    @Test
    fun `the adapter's delete of rows created before the cutoff removes only the row strictly before it`() {
        saveRow("after", cutoff + 1)
        saveRow("at", cutoff)
        saveRow("before", cutoff - 1)
        // Same id as the row that goes, and old enough for its own sweep: only a delete that reached
        // into the tombstones, by id or by time, would take it.
        saveTombstone("before", cutoff - 1)

        val removed = runAdapterDelete(AdapterStatements.deleteCreatedBefore, cutoff.toString())

        assertEquals(1, removed)
        assertEquals(
            "a row at the cutoff is in date; only the millisecond before it goes",
            listOf<Any?>("after", "at"),
            ids(),
        )
        assertEquals("the delete of rows leaves the tombstones alone", listOf<Any?>("before"), tombstoneIds())
    }

    @Test
    fun `the adapter's delete of tombstones recorded before the cutoff removes only the one strictly before it`() {
        saveTombstone("after", cutoff + 1)
        saveTombstone("at", cutoff)
        saveTombstone("before", cutoff - 1)
        // Same id as the tombstone that goes, and old enough for the purge: a delete that cascaded into
        // the transcriptions, by id or by time, would take it.
        saveRow("before", cutoff - 1)

        val removed = runAdapterDelete(AdapterStatements.deleteTombstonesRecordedBefore, cutoff.toString())

        assertEquals(1, removed)
        assertEquals(
            "a tombstone at the cutoff is in date; only the millisecond before it goes",
            listOf<Any?>("after", "at"),
            tombstoneIds(),
        )
        assertEquals("the sweep of tombstones leaves the transcriptions alone", listOf<Any?>("before"), ids())
    }

    @Test
    fun `the adapter's delete by id removes the row with exactly that id and no other`() {
        // Ids that differ from "ab" by case, by a wildcard character and by a suffix. A delete that matched
        // with LIKE, without regard to case, by prefix, on any other column, or on every row would take
        // more than the one row.
        saveRow("ab", 1_000L)
        saveRow("AB", 1_000L)
        saveRow("a%", 1_000L)
        saveRow("ab-2", 1_000L)

        val removed = runAdapterDelete(AdapterStatements.deleteById, "ab")

        assertEquals(1, removed)
        assertEquals(listOf<Any?>("AB", "a%", "ab-2"), ids())
    }

    // ── a cutoff bound as text still compares as a number ────────────────

    @Test
    fun `a text-bound cutoff compares as a number, not as text`() {
        // As text, "99" sorts after "100" ('9' is after '1'); as numbers 99 is
        // before 100. If the comparison were textual, the row at 99 would be
        // kept and this list would be empty.
        saveRow("ninety-nine", 99L)
        saveRow("one-hundred", 100L)
        saveRow("one-hundred-one", 101L)

        val selected = sqlite.firstColumn(HistorySql.SELECT_IDS_CREATED_BEFORE, "100")

        assertEquals(listOf<Any?>("ninety-nine"), selected)
    }

    @Test
    fun `a column declared as text compares the same bound cutoff as text, so the wrong rows are chosen`() {
        // The control for the test above: the same three rows, the same predicate and the same text-bound
        // cutoff, but the column is declared TEXT. If this harness could not see the difference, it
        // could not have shown that the real column is safe.
        sqlite.connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE scratch_text (id TEXT PRIMARY KEY NOT NULL, created_at TEXT NOT NULL)")
        }
        for ((id, createdAt) in listOf("ninety-nine" to 99L, "one-hundred" to 100L, "one-hundred-one" to 101L)) {
            sqlite.exec("INSERT INTO scratch_text (id, created_at) VALUES (?, ?)", id, createdAt)
        }
        assertEquals(
            "the numbers went in as text, which is the state this control needs",
            listOf<Any?>("text", "text", "text"),
            sqlite.firstColumn("SELECT typeof(created_at) FROM scratch_text ORDER BY id"),
        )

        val selected = sqlite.firstColumn(
            "SELECT id FROM scratch_text WHERE ${HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE} ORDER BY id",
            "100",
        )

        assertEquals(
            "as text, '99' is after '100', so the row that is numerically before it is missed",
            emptyList<Any?>(),
            selected,
        )
    }

    // ── the rest of the statements, run ──────────────────────────────────

    @Test
    fun `history is listed newest first, ties broken by id, with the limit bound as text`() {
        // Two tied pairs, saved in opposite id order: "m" before "n", and "r" before
        // "q". Without an id tiebreak SQLite hands ties back in save order or in
        // reverse save order, depending on how it plans the query; one pair is wrong
        // under either, so a missing tiebreak cannot pass by luck.
        saveRow("a", 1_000L)
        saveRow("r", 2_000L)
        saveRow("q", 2_000L)
        saveRow("m", 3_000L)
        saveRow("n", 3_000L)

        assertEquals(listOf<Any?>("n", "m", "r", "q", "a"), sqlite.firstColumn(HistorySql.SELECT_NEWEST, "10"))
        assertEquals("the limit is honoured", listOf<Any?>("n", "m"), sqlite.firstColumn(HistorySql.SELECT_NEWEST, "2"))
    }

    @Test
    fun `saving the same id twice leaves one row, the later one`() {
        saveRow("t-1", 5_000L, text = "first try")
        saveRow("t-1", 5_000L, text = "second try")

        assertEquals(listOf<Any?>(1L), sqlite.firstColumn(HistorySql.COUNT_TRANSCRIPTIONS))
        assertEquals(
            listOf<Any?>("second try"),
            sqlite.firstColumn("SELECT text FROM ${HistorySql.TABLE_TRANSCRIPTIONS}"),
        )
    }

    @Test
    fun `a save writes every column where the column list says it goes`() {
        sqlite.exec(HistorySql.INSERT_OR_REPLACE, "id-1", "the text", "server", "large-v3", 4_321L, 7_000L, "a.wav")

        val row = sqlite
            .query("SELECT ${HistorySql.TRANSCRIPTION_COLUMNS} FROM ${HistorySql.TABLE_TRANSCRIPTIONS}")
            .single()

        assertEquals(listOf<Any?>("id-1", "the text", "server", "large-v3", 4_321L, 7_000L, "a.wav"), row)
    }

    @Test
    fun `empty text is stored, listed, counted and purged like any other`() {
        saveRow("silent-old", cutoff - 1, text = "")
        saveRow("silent-edge", cutoff, text = "")

        assertEquals(listOf<Any?>(2L), sqlite.firstColumn(HistorySql.COUNT_TRANSCRIPTIONS))
        assertEquals(
            listOf<Any?>(""),
            sqlite.firstColumn("SELECT text FROM ${HistorySql.TABLE_TRANSCRIPTIONS} WHERE id = 'silent-edge'"),
        )
        assertEquals(listOf<Any?>("silent-edge", "silent-old"), sqlite.firstColumn(HistorySql.SELECT_NEWEST, "10"))
        assertEquals(
            listOf<Any?>("silent-old"),
            sqlite.firstColumn(HistorySql.SELECT_IDS_CREATED_BEFORE, cutoff.toString()),
        )

        assertEquals(1, runAdapterDelete(AdapterStatements.deleteCreatedBefore, cutoff.toString()))
        assertEquals(listOf<Any?>("silent-edge"), ids())
        assertEquals(
            "the row that stays keeps its empty text",
            listOf<Any?>(""),
            sqlite.firstColumn("SELECT text FROM ${HistorySql.TABLE_TRANSCRIPTIONS} WHERE id = 'silent-edge'"),
        )
    }

    @Test
    fun `a tombstone is one row per id, and they are listed newest first, ties by id`() {
        // "a" is saved again as a user delete: one row, the later one. It then ties
        // with "z" at 2000, and "m" and "n" tie at 3000, saved in opposite id order,
        // so a missing id tiebreak is wrong under either tie order SQLite may use.
        saveTombstone("z", 2_000L)
        saveTombstone("a", 1_000L)
        sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, "a", 2_000L, "user")
        saveTombstone("m", 3_000L)
        saveTombstone("n", 3_000L)

        val rows = sqlite.query(HistorySql.SELECT_NEWEST_TOMBSTONES, "10")

        assertEquals(
            listOf(
                listOf<Any?>("n", 3_000L, "retention"),
                listOf<Any?>("m", 3_000L, "retention"),
                listOf<Any?>("z", 2_000L, "retention"),
                listOf<Any?>("a", 2_000L, "user"),
            ),
            rows,
        )
    }

    @Test
    fun `the schema can be created again on an existing database`() {
        // Defensive: the phone creates the schema once per database file. What the run adds to reading the
        // text is that a statement which says IF NOT EXISTS only in a comment still fails here.
        saveRow("t-1", 1_000L)

        sqlite.createSchema()

        assertEquals(listOf<Any?>("t-1"), ids())
    }

    // ── the in-memory double and real SQL agree ──────────────────────────

    private val cutoffs = listOf(0L, 1L, cutoff - 1, cutoff, cutoff + 1, Long.MAX_VALUE)

    /** Instants on both sides of every cutoff; "b" and "a" tie, and are saved in reverse id order. */
    private val stamped = listOf(
        "epoch" to 0L,
        "one" to 1L,
        "b" to cutoff - 1,
        "a" to cutoff - 1,
        "at" to cutoff,
        "after" to cutoff + 1,
        "last" to Long.MAX_VALUE,
    )

    /** The ids each cutoff takes from [stamped], in the order they are taken. */
    private val expectedBefore = mapOf<Long, List<String>>(
        0L to emptyList(),
        1L to listOf("epoch"),
        cutoff - 1 to listOf("epoch", "one"),
        cutoff to listOf("epoch", "one", "a", "b"),
        cutoff + 1 to listOf("epoch", "one", "a", "b", "at"),
        Long.MAX_VALUE to listOf("epoch", "one", "a", "b", "at", "after"),
    )

    private fun row(id: String, createdAt: Long) = TranscriptionRow(
        id = id,
        text = "text for $id",
        source = TranscriptionRow.SOURCE_LOCAL,
        model = "small.en",
        durationMs = 1_000L,
        createdAt = createdAt,
    )

    @Test
    fun `the in-memory database and real SQL select and remove the same rows at every cutoff`() {
        for (at in cutoffs) {
            val memory = InMemoryHistoryDatabase()
            stamped.forEach { (id, createdAt) -> memory.save(row(id, createdAt)) }
            DesktopSqlite().use { engine ->
                val real = JdbcHistoryDatabase(engine)
                stamped.forEach { (id, createdAt) -> real.save(row(id, createdAt)) }
                val expected = expectedBefore.getValue(at)

                assertEquals("the double is the reference at cutoff $at", expected, memory.idsCreatedBefore(at))
                assertEquals("ids selected at cutoff $at", expected, real.idsCreatedBefore(at))
                val removed = memory.deleteCreatedBefore(at)
                assertEquals("the double removes what it selected at cutoff $at", expected.size, removed)
                assertEquals("rows removed at cutoff $at", removed, real.deleteCreatedBefore(at))
                assertEquals(
                    "rows left at cutoff $at",
                    memory.newest(100).map { it.id },
                    real.newest(100).map { it.id },
                )
            }
        }
    }

    @Test
    fun `the in-memory database and real SQL sweep the same tombstones at every cutoff`() {
        for (at in cutoffs) {
            val memory = InMemoryHistoryDatabase()
            stamped.forEach { (id, stamp) -> memory.putTombstone(Tombstone(id, stamp, Tombstone.Reason.USER)) }
            DesktopSqlite().use { engine ->
                val real = JdbcHistoryDatabase(engine)
                stamped.forEach { (id, stamp) -> real.putTombstone(Tombstone(id, stamp, Tombstone.Reason.USER)) }
                val expected = expectedBefore.getValue(at).size

                val swept = memory.deleteTombstonesRecordedBefore(at)
                assertEquals("the double sweeps the reference count at cutoff $at", expected, swept)
                assertEquals("tombstones removed at cutoff $at", swept, real.deleteTombstonesRecordedBefore(at))
                assertEquals(
                    "tombstones left at cutoff $at",
                    memory.newestTombstones(100),
                    real.newestTombstones(100),
                )
            }
        }
    }
}
