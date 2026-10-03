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
 * Every check is a function of the source text, and every test here is a refusal: a made-up adapter with
 * one mistake planted in it, run to see that the check for that mistake is the one that refuses, that its
 * message says what was found and what was expected, and that the checks for the other shapes still pass.
 * The passes themselves are in [AdapterBindingsTest], which runs the checks against the real adapter.
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

internal class AdapterBindingsReadsTest {

    // ── a source the checks cannot read ──────────────────────────────────

    @Test
    fun `a bound array written in a shape the check cannot read is refused, and the message says so`() {
        val unreadable = render().replacedFirst("arrayOf<Any?>(", "listOf(")

        assertRefusedBy(setOf("save"), unreadable, "are written as `listOf(", "expected arrayOf<...>(")
    }

    @Test
    fun `an execSQL call with another number of arguments, or a second one, is refused`() {
        val extraArgument = render()
            .replacedFirst("helper.writableDatabase.execSQL(", "helper.writableDatabase.execSQL(x, ")
        val secondCall = render().replacedFirst(
            "override fun save(row: TranscriptionRow) {",
            "override fun save(row: TranscriptionRow) {\n" +
                "    helper.writableDatabase.execSQL(${expected.saveStatement}, arrayOf<Any?>())",
        )

        assertRefusedBy(setOf("save"), extraArgument, "the execSQL call in save has 3 arguments, expected 2")
        assertRefusedBy(setOf("save"), secondCall, "save holds 2 execSQL( calls, expected exactly 1")
    }

    @Test
    fun `a method the check needs that cannot be found is refused, and the message names it`() {
        val renamed = render().replacedFirst("override fun save(", "override fun store(")

        assertRefusedBy(setOf("save"), renamed, "expected exactly one method named save, found 0")
    }

    @Test
    fun `a statement whose columns cannot be read, or whose placeholders do not match them, is refused`() {
        val notAnInsert = assertThrows(AdapterShapeError::class.java) { insertColumns("SELECT id FROM t") }
        val fewerPlaceholders = assertThrows(AdapterShapeError::class.java) {
            insertColumns("INSERT OR REPLACE INTO t (a, b) VALUES (?)")
        }
        val notASelect = assertThrows(AdapterShapeError::class.java) { selectColumns("DELETE FROM t") }

        assertTrue(notAnInsert.message, notAnInsert.message.orEmpty().contains("cannot read a column list"))
        assertTrue(fewerPlaceholders.message, fewerPlaceholders.message.orEmpty().contains("2 columns"))
        assertTrue(notASelect.message, notASelect.message.orEmpty().contains("cannot read a select list"))
        assertEquals(listOf("a", "b"), insertColumns("INSERT OR REPLACE INTO t (a, b) VALUES (?, ?)"))
        assertEquals(listOf("a", "COUNT(b, c)"), selectColumns("SELECT a, COUNT(b, c) FROM t WHERE 1"))
    }

    // ── values read from the wrong column ────────────────────────────────

    @Test
    fun `two cursor positions swapped in the row mapper are refused by the row check alone`() {
        val reads = expected.rowReads
        for ((first, second) in listOf(4 to 5, 0 to 1)) {
            val swapped = reads.rowPositionsSwapped(first, second)

            assertRefusedBy(
                setOf("rowMapper"),
                render(expected.copy(rowReads = swapped)),
                "reads ${reads[first].property} as `${swapped[first].text}`, expected `${reads[first].text}`",
            )
        }
    }

    @Test
    fun `a getter of the wrong type in the row mapper is refused`() {
        val reads = expected.rowReads
        val wrong = reads[1].copy(getter = "getLong")

        assertRefusedBy(
            setOf("rowMapper"),
            render(expected.copy(rowReads = reads.replacedAt(1, wrong))),
            "reads ${wrong.property} as `${wrong.text}`, expected `${reads[1].text}`",
        )
    }

    @Test
    fun `an isNull at another index than the read, or none at all, is refused for the nullable property`() {
        val reads = expected.rowReads
        val position = reads.indexOfFirst { it.nullable }
        val audio = reads[position]
        val shifted = audio.copy(nullIndex = audio.index - 1)
        val plain = audio.copy(nullable = false)

        assertRefusedBy(
            setOf("nullableRowMapper"),
            render(expected.copy(rowReads = reads.replacedAt(position, shifted))),
            "reads ${audio.property} as `${shifted.text}`, expected `${audio.text}`",
        )
        assertRefusedBy(
            setOf("nullableRowMapper"),
            render(expected.copy(rowReads = reads.replacedAt(position, plain))),
            "reads ${audio.property} as `${plain.text}`, expected `${audio.text}`",
        )
    }

    @Test
    fun `a property missing from the row mapper, or one added, is refused`() {
        val reads = expected.rowReads
        val count = reads.size
        val added = reads + RowRead("extra", "getString", count, nullable = false)

        assertRefusedBy(
            setOf("rowMapper", "nullableRowMapper"),
            render(expected.copy(rowReads = reads.dropLast(1))),
            "has ${count - 1} arguments, expected $count",
        )
        assertRefusedBy(
            setOf("rowMapper", "nullableRowMapper"),
            render(expected.copy(rowReads = added)),
            "has ${count + 1} arguments, expected $count",
        )
    }

    @Test
    fun `a select list in another order than the row columns is refused`() {
        val columns = pieces(HistorySql.TRANSCRIPTION_COLUMNS)
        val reordered = columns.swapped(1, 2)

        val refusal = assertThrows(AdapterShapeError::class.java) {
            checkSelectOrder("SELECT ${reordered.joinToString(", ")} FROM transcriptions ORDER BY id")
        }

        assertTrue(
            refusal.message,
            refusal.message.orEmpty().contains("selects $reordered, expected the row columns in order: $columns"),
        )
        checkSelectOrder("SELECT ${columns.joinToString(", ")} FROM transcriptions ORDER BY id")
    }

    @Test
    fun `a newest that queries another statement, or maps its rows another way, is refused`() {
        val unmapped = render().replacedFirst("add(cursor.toTranscriptionRow())", "add(cursor)")

        assertRefusedBy(
            setOf("newest"),
            render(expected.copy(newestStatement = expected.idsStatement)),
            "newest queries `${expected.idsStatement}`, expected ${expected.newestStatement}",
        )
        assertRefusedBy(setOf("newest"), unmapped, "newest maps its rows through toTranscriptionRow() 0 times")
    }

    @Test
    fun `two cursor positions swapped in the tombstone reads are refused`() {
        val reads = expected.tombstoneReads
        val swapped = reads.tombstonePositionsSwapped(0, reads.lastIndex)

        assertRefusedBy(
            setOf("tombstoneReads"),
            render(expected.copy(tombstoneReads = swapped)),
            "newestTombstones reads ${swapped.sortedBy { it.local }}, expected ${reads.sortedBy { it.local }}",
        )
    }

    @Test
    fun `a wrong getter or an extra read in the tombstone reads is refused`() {
        val reads = expected.tombstoneReads
        val wrong = reads.replacedAt(1, reads[1].copy(getter = "getString"))
        val added = reads + TombstoneRead("extra", "getString", reads.size)

        assertRefusedBy(
            setOf("tombstoneReads"),
            render(expected.copy(tombstoneReads = wrong)),
            "newestTombstones reads ${wrong.sortedBy { it.local }}",
        )
        assertRefusedBy(
            setOf("tombstoneReads"),
            render(expected.copy(tombstoneReads = added)),
            "newestTombstones reads ${added.sortedBy { it.local }}",
        )
    }

    @Test
    fun `a reason decoded from the wrong local, or a constructor argument taken from one, is refused`() {
        val arguments = expected.tombstoneArguments
        val crossed = arguments.replacedAt(0, "${expected.tombstoneProperties[0]} = ${expected.reasonLocal}")

        assertRefusedBy(
            setOf("tombstoneReads"),
            render(expected.copy(reasonLocal = expected.tombstoneProperties[0])),
            "gives Reason.fromStored [${expected.tombstoneProperties[0]}], expected [${expected.reasonLocal}]",
        )
        assertRefusedBy(
            setOf("tombstoneReads"),
            render(expected.copy(tombstoneArguments = crossed)),
            "builds its Tombstone from $crossed, expected $arguments",
        )
    }

    @Test
    fun `an id read at another position, as another type, or not read at all is refused`() {
        for (planted in listOf("getString(1)", "getLong(0)", "hash()")) {
            val found = if (planted == "hash()") "[]" else "[$planted]"

            assertRefusedBy(
                setOf("idRead"),
                render(expected.copy(idRead = planted)),
                "idsCreatedBefore reads $found from its cursor, expected exactly [${expected.idRead}]",
            )
        }
    }

    @Test
    fun `a count read at another position or as another type is refused`() {
        for (planted in listOf("getInt(1)", "getLong(0)")) {
            assertRefusedBy(
                setOf("countRead"),
                render(expected.copy(countRead = planted)),
                "countTranscriptions reads [$planted] from its cursor, expected exactly [${expected.countRead}]",
            )
        }
    }
}
