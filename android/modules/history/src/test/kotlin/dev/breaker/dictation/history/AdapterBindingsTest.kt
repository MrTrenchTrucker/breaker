package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order in which the phone's adapter binds its values, and the columns it reads them back from.
 *
 * `SqliteHistoryDatabase` runs in no JVM test, and [JdbcHistoryDatabase] binds and reads from a copy of these
 * orders typed out again, so an adapter that swapped two bound values, or read a column at the wrong position,
 * would leave every other test green and store or show the wrong data. This reads the adapter's source text
 * through [AdapterStatements] and checks it against what the module itself says: the column list of each
 * `INSERT`, the select list of each query, and the constructors of [TranscriptionRow] and [Tombstone]. Neither
 * an order nor a position is typed out again here.
 *
 * Every check is a function of the source text, so each test here runs the checks against the real
 * adapter. The refusals are exercised in [AdapterBindingsReadsTest], which plants one mistake at a time
 * in a made-up adapter; this class holds the passes, so that a check which refused everything would be
 * caught here rather than only there.
 *
 * **What a check refuses.** A method that cannot be found, a call with another number of arguments than the
 * check expects, a bound array written in a shape it cannot read, and a value bound or read in the wrong place
 * or with the wrong getter. Each is an [AdapterShapeError] that says what it found and what it expected. A
 * check that finds no read, or no bound value, where it expects some is a refusal, never a pass.
 *
 * **What it cannot see.** It reads text, so a reformat that moves a value out of the shape it reads is
 * refused, not passed. The reason of a tombstone is followed through the local named `stored`, the argument of
 * `Reason.fromStored` and the arguments of the `Tombstone(` call, all as they are written. What the platform
 * does with the values it is given, and with the cursor it hands back, is not checked: only that the adapter
 * gives and reads them in the order the statements name.
 */

internal class AdapterBindingsTest {

    // ── the real adapter ─────────────────────────────────────────────────

    @Test
    fun `save runs the insert and binds the row properties in the order of the insert columns`() {
        checkSave(adapter)
    }

    @Test
    fun `putTombstone runs the tombstone insert and binds its id, deletion time and stored reason in column order`() {
        checkPutTombstone(adapter)
    }

    @Test
    fun `the row mapper reads every property at the position of its column with the getter for its type`() {
        checkRowMapper(adapter, nullable = false)
    }

    @Test
    fun `the row mapper reads the nullable audio path through isNull at the index it reads it from`() {
        checkRowMapper(adapter, nullable = true)
    }

    @Test
    fun `the newest query selects the row columns in the order the row mapper reads them`() {
        checkNewest(adapter)
        checkSelectOrder(HistorySql.SELECT_NEWEST)
    }

    @Test
    fun `the tombstone query is read at the positions of the id, the deletion time and the reason`() {
        checkTombstoneReads(adapter)
    }

    @Test
    fun `the query for the ids a purge will tombstone is read from the column that selects the id`() {
        checkIdRead(adapter)
    }

    @Test
    fun `the count is read as an int from the one column the count selects`() {
        checkCountRead(adapter)
    }

    // ── an adapter written correctly ─────────────────────────────────────

    @Test
    fun `an adapter written correctly is accepted by every check`() {
        assertEquals(emptyMap<String, String>(), refusals(render()))
    }

    @Test
    fun `a reformatted adapter is accepted, with comments between the values and then all on one line`() {
        val commented = render(note = "// kept")
        val oneLine = render(note = "/* kept */").lines().joinToString(" ") { it.trim() }

        assertTrue("the planted reformat left the source as it was", commented != render() && '\n' !in oneLine)
        assertEquals(emptyMap<String, String>(), refusals(commented))
        assertEquals(emptyMap<String, String>(), refusals(oneLine))
    }

    // ── values bound out of order ────────────────────────────────────────

    @Test
    fun `two bound values swapped in save are refused by the save check alone`() {
        val bindings = expected.saveBindings
        for ((first, second) in listOf(1 to 2, 0 to bindings.lastIndex)) {
            val swapped = bindings.swapped(first, second)

            assertRefusedBy(
                setOf("save"),
                render(expected.copy(saveBindings = swapped)),
                "save binds $swapped, expected $bindings",
            )
        }
    }

    @Test
    fun `a bound value missing from save, or one added, is refused`() {
        val bindings = expected.saveBindings

        assertRefusedBy(
            setOf("save"),
            render(expected.copy(saveBindings = bindings.dropLast(1))),
            "save binds ${bindings.dropLast(1)}, expected $bindings",
        )
        assertRefusedBy(
            setOf("save"),
            render(expected.copy(saveBindings = bindings + bindings.first())),
            "save binds ${bindings + bindings.first()}, expected $bindings",
        )
    }

    @Test
    fun `a save that runs another statement than the save insert is refused`() {
        assertRefusedBy(
            setOf("save"),
            render(expected.copy(saveStatement = expected.tombstoneStatement)),
            "save runs `${expected.tombstoneStatement}`, expected ${expected.saveStatement}",
        )
    }

    @Test
    fun `the deletion time and the stored reason swapped in putTombstone are refused`() {
        val bindings = expected.tombstoneBindings
        val swapped = bindings.swapped(1, 2)

        assertRefusedBy(
            setOf("putTombstone"),
            render(expected.copy(tombstoneBindings = swapped)),
            "putTombstone binds $swapped, expected $bindings",
        )
    }

    @Test
    fun `a reason bound without its stored text is refused`() {
        val bindings = expected.tombstoneBindings
        val bare = bindings.map { it.removeSuffix(".stored") }
        assertTrue("the planted edit left the bindings as they were", bare != bindings)

        assertRefusedBy(
            setOf("putTombstone"),
            render(expected.copy(tombstoneBindings = bare)),
            "putTombstone binds $bare, expected $bindings",
        )
    }

}
