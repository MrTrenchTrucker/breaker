package dev.breaker.dictation.history

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tombstone gate fires on every shape that carries the mapping, not on two written-out ones.
 *
 * [AdapterTextRules.checkUnknownReasonThrows] reads the three databases' `newestTombstones` bodies as text.
 * The gate is about what the read does with a stored word it does not know: the word is handed back as a
 * `Tombstone.StoredRow`, and the mapping to a [Tombstone.Reason] is the store's, where an unmappable row is
 * skipped and reported. A body that maps it itself, or that stops the read on it, loses the tombstones
 * behind that row.
 *
 * Two of the checks were pinned to one written shape each, so the other shapes passed. This file plants each
 * of them: a `fromStored(` carried by a `return`, by an elvis `?:` or by a `map { }`, and a body that aborts
 * the read with a `throw`, `error(`, `check(`, `require(`, `checkNotNull(`, `requireNotNull(` or `!!`.
 * Every test here asserts the gate REFUSES the shape — a shape that passes is not a sample. The two shapes
 * the gate already knew live in `AdapterTextQueriesTest`, which stays as it is.
 *
 * A rule that cannot read the shape it is given throws [AdapterShapeError] and says what it found and what it
 * expected; it never passes on a shape it cannot read.
 */
class AdapterTombstoneShapesTest {

    // ── the real databases and the real store ────────────────────────────

    @Test
    fun `the three real databases still hand the stored word back and the store still skips and reports it`() {
        AdapterTextRules.checkUnknownReasonThrows(
            adapterSource = AdapterStatements.adapterSource(),
            jdbcSource = moduleSource("JdbcHistoryDatabase.kt"),
            inMemorySource = moduleSource("InMemoryHistoryDatabase.kt"),
            storeSource = storeSource(),
        )
    }

    // ── the mapping, in every shape ──────────────────────────────────────

    @Test
    fun `a database body that returns a mapping is refused`() {
        val refused = refusal(
            db("return Tombstone.Reason.fromStored(cursor.getString(2))"),
        )

        assertTrue(refused, refused.contains("still calls fromStored"))
    }

    @Test
    fun `a database body that maps through an elvis is refused`() {
        val refused = refusal(
            db("val reason = Tombstone.Reason.fromStored(cursor.getString(2)) ?: cursor.getString(2)"),
        )

        assertTrue(refused, refused.contains("still calls fromStored"))
    }

    @Test
    fun `a database body that maps inside a map block is refused`() {
        val refused = refusal(
            db("map { Tombstone.Reason.fromStored(it) }"),
        )

        assertTrue(refused, refused.contains("still calls fromStored"))
    }

    @Test
    fun `a database body that maps through a local function is refused`() {
        // The gate reads the method's own range, so a call handed to a local function is still a call here.
        val refused = refusal(
            db(
                "val read = fun(word: String) = Tombstone.Reason.fromStored(word)\n" +
                    "                        read(cursor.getString(2))",
            ),
        )

        assertTrue(refused, refused.contains("still calls fromStored"))
    }

    @Test
    fun `a database body that maps with the receiver dots is refused, as the shape it already knew`() {
        val refused = refusal(
            db("val reason = Tombstone . Reason . fromStored(cursor.getString(2))"),
        )

        assertTrue(refused, refused.contains("still calls fromStored"))
    }

    // ── the read that refuses the row, in every shape ────────────────────

    @Test
    fun `a database body that throws an IllegalStateException is refused`() {
        val refused = refusal(
            db("throw IllegalStateException(\"unknown reason '\" + cursor.getString(2) + \"'\")"),
        )

        assertTrue(refused, refused.contains("still throws"))
    }

    @Test
    fun `a database body that aborts with error is refused`() {
        val refused = refusal(
            db("error(\"unknown reason '\" + cursor.getString(2) + \"'\")"),
        )

        assertTrue(refused, refused.contains("refuses a stored row with error(, check(, require("))
    }

    @Test
    fun `a database body that aborts with check is refused`() {
        val refused = refusal(
            db("check(Tombstone.Reason.fromStored(cursor.getString(2)) != null) { \"unknown\" }"),
        )

        assertTrue(refused, refused.contains("refuses a stored row with error(, check(, require("))
    }

    @Test
    fun `a database body that aborts with require is refused`() {
        val refused = refusal(
            db("require(Tombstone.Reason.fromStored(cursor.getString(2)) != null) { \"unknown\" }"),
        )

        assertTrue(refused, refused.contains("refuses a stored row with error(, check(, require("))
    }

    @Test
    fun `a database body that aborts with checkNotNull is refused`() {
        // The null form of the same idiom, and a name the plain `check(` alternative does not reach.
        val refused = refusal(
            db("val reason = checkNotNull(cursor.getString(2))"),
        )

        assertTrue(refused, refused.contains("refuses a stored row with error(, check(, require(, checkNotNull("))
    }

    @Test
    fun `a database body that aborts with requireNotNull is refused`() {
        val refused = refusal(
            db("val reason = requireNotNull(cursor.getString(2))"),
        )

        assertTrue(refused, refused.contains("refuses a stored row with error(, check(, require(, checkNotNull("))
    }

    @Test
    fun `a database body that asserts the stored word with the not-null operator is refused`() {
        // `!!` is the operator form of the same fail: a null stored word throws instead of coming back.
        val refused = refusal(
            db("val reason = cursor.getString(2)!!"),
        )

        assertTrue(refused, refused.contains("fails the read with the not-null operator !!"))
    }

    @Test
    fun `a database body that asserts with no space before a double equals is refused`() {
        // A `!!` at once followed by `==`, no space between: the assertion operator runs into the comparison.
        val refused = refusal(
            db("val reason = cursor.getString(2)!!==x"),
        )

        assertTrue(refused, refused.contains("fails the read with the not-null operator !!"))
    }

    @Test
    fun `a database body that only compares with not-equals is not refused`() {
        // `!=` must not be read as `!!`, and a body that hands the word back stays accepted.
        AdapterTextRules.checkUnknownReasonThrows(
            db(
                "q.rawQuery(S, a).use { cursor ->\n" +
                    "                buildList {\n" +
                    "                    while (cursor.moveToNext()) {\n" +
                    "                        val word = cursor.getString(2)\n" +
                    "                        if (word != null) {\n" +
                    "                            add(Tombstone.StoredRow(cursor.getString(0), cursor.getLong(1), word))\n" +
                    "                        }\n" +
                    "                    }\n" +
                    "                }\n" +
                    "            }",
            ),
            db(),
            db(),
            store(),
        )
    }

    // ── the store's own limits stay where they are ───────────────────────

    @Test
    fun `the store's own limit check is not refused, because it is the store's to keep`() {
        // The store keeps require(limit >= 0): a negative limit is not a row to skip and report. Only the
        // three database bodies refuse a require(.
        AdapterTextRules.checkUnknownReasonThrows(db(), db(), db(), store())
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** The message of the refusal the gate gives for [body] in the first database source, or a failure if none. */
    private fun refusal(body: String): String =
        assertThrows(AdapterShapeError::class.java) {
            AdapterTextRules.checkUnknownReasonThrows(body, db(), db(), store())
        }.message.orEmpty()

    /** The store's source, as it is on disk. */
    private fun storeSource(): String {
        val files = ModuleFiles.mainSources().filter { it.name == "SqliteHistoryStore.kt" }
        check(files.size == 1) { "expected exactly one SqliteHistoryStore.kt, found ${files.size}" }
        return files.single().readText()
    }

    /** A Kotlin file of this module, by name, under the main or the test sources. */
    private fun moduleSource(name: String): String {
        val roots = listOf(File(ModuleFiles.moduleRoot, "src/main/kotlin"), File(ModuleFiles.moduleRoot, "src/test/kotlin"))
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == name } }.toList()
        check(files.size == 1) { "expected exactly one $name in the module's Kotlin sources, found ${files.size}" }
        return files.single().readText()
    }

    /** A database whose `newestTombstones` hands the stored row back, with that body replaced at will. */
    private fun db(
        tombstones: String = "q.rawQuery(S, a).use { cursor ->\n" +
            "                buildList {\n" +
            "                    while (cursor.moveToNext()) {\n" +
            "                        add(Tombstone.StoredRow(cursor.getString(0), cursor.getLong(1), cursor.getString(2)))\n" +
            "                    }\n" +
            "                }\n" +
            "            }",
    ): String =
        "internal class Db : HistoryDatabase {\n" +
            "    override fun newestTombstones(limit: Int): List<Tombstone.StoredRow> =\n        $tombstones\n\n" +
            "    override fun countTranscriptions(): Int = 0\n" +
            "}\n"

    /** A store whose `pendingTombstones` maps, skips and reports, with that body replaced at will. */
    private fun store(
        pending: String = "database.newestTombstones(limit).mapNotNull { row ->\n" +
            "                if (row.toTombstoneOrNull() == null) {\n" +
            "                    report(\"history tombstone \${row.id} has an unknown reason '\${row.reason}'; it stays stored\")\n" +
            "                    null\n" +
            "                } else {\n" +
            "                    row.toTombstone()\n" +
            "                }\n" +
            "            }",
    ): String =
        "internal class Store {\n" +
            "    fun pendingTombstones(limit: Int): List<Tombstone> {\n" +
            "        require(limit >= 0) { \"limit cannot be negative\" }\\n" +
            "        return $pending\n" +
            "    }\n" +
            "}\n"
}
