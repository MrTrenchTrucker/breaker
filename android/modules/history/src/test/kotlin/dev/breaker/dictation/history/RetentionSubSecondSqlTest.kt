package dev.breaker.dictation.history

import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The retention SQL at a `now` that has a sub-second part, with the window taken from [RetentionPolicy].
 *
 * The rows and the tombstones sit at the cutoff and 1 ms and 100 ms either side of it, on a real SQLite
 * ([DesktopSqlite]). The cutoff handed to the SQL is the policy's own, for `now` = 12:00:00.500, so a cutoff
 * cut to whole seconds would keep the two rows just before it. The tombstone sweep runs the delete the
 * adapter ships ([AdapterStatements]), bound as text like the adapter binds it.
 */
class RetentionSubSecondSqlTest {

    private val sqlite = DesktopSqlite()

    private val policy = RetentionPolicy()

    private val now = Instant.parse("2026-06-30T12:00:00.500Z")

    @After
    fun closeDatabase() = sqlite.close()

    private fun saveRow(id: String, createdAt: Long) {
        sqlite.exec(HistorySql.INSERT_OR_REPLACE, id, "load the hay", "local", "small.en", 1_200L, createdAt, null)
    }

    private fun saveTombstone(id: String, deletedAt: Long) {
        sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, id, deletedAt, "retention")
    }

    @Test
    fun `the retention select takes the rows before a sub-second cutoff and keeps the one at it`() {
        val cutoff = policy.cutoff(now).toEpochMilli()
        assertEquals("the cutoff, worked out by hand", 1_775_044_800_500L, cutoff)
        saveRow("a-100ms-before", cutoff - 100)
        saveRow("b-1ms-before", cutoff - 1)
        saveRow("c-at-cutoff", cutoff)
        saveRow("d-1ms-after", cutoff + 1)

        val selected = sqlite.firstColumn(HistorySql.SELECT_IDS_CREATED_BEFORE, cutoff.toString())

        assertEquals(listOf<Any?>("a-100ms-before", "b-1ms-before"), selected)
    }

    @Test
    fun `the shipped delete of rows removes the same two and keeps the one at a sub-second cutoff`() {
        val cutoff = policy.cutoff(now).toEpochMilli()
        assertEquals("the cutoff, worked out by hand", 1_775_044_800_500L, cutoff)
        saveRow("a-100ms-before", cutoff - 100)
        saveRow("b-1ms-before", cutoff - 1)
        saveRow("c-at-cutoff", cutoff)
        saveRow("d-1ms-after", cutoff + 1)
        val shipped = AdapterStatements.deleteCreatedBefore

        val removed = sqlite.delete(shipped.table, shipped.where, cutoff.toString())

        assertEquals(2, removed)
        assertEquals(
            listOf<Any?>("c-at-cutoff", "d-1ms-after"),
            sqlite.firstColumn("SELECT id FROM ${HistorySql.TABLE_TRANSCRIPTIONS} ORDER BY id"),
        )
    }

    @Test
    fun `the tombstone sweep drops the tombstones before its window and keeps the one at it`() {
        val cutoff = policy.tombstoneCutoff(now).toEpochMilli()
        assertEquals("the tombstone cutoff, worked out by hand", 1_775_044_800_500L, cutoff)
        saveTombstone("a-100ms-before", cutoff - 100)
        saveTombstone("b-1ms-before", cutoff - 1)
        saveTombstone("c-at-cutoff", cutoff)
        saveTombstone("d-now", now.toEpochMilli())
        val shipped = AdapterStatements.deleteTombstonesRecordedBefore

        val dropped = sqlite.delete(shipped.table, shipped.where, cutoff.toString())

        assertEquals(2, dropped)
        assertEquals(
            listOf<Any?>("c-at-cutoff", "d-now"),
            sqlite.firstColumn("SELECT id FROM ${HistorySql.TABLE_TOMBSTONES} ORDER BY id"),
        )
    }
}
