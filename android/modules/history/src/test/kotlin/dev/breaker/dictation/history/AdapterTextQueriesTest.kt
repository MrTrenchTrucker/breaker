package dev.breaker.dictation.history

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the phone's adapter does with a cursor and with a stored value it does not know, read as text.
 *
 * `SqliteHistoryDatabase` runs in no JVM test, so nothing else notices a cursor that is never closed, a
 * database that throws on a stored reason it does not know instead of handing the word back, or a count that
 * answers something other than 0 for an empty table. The rules are in [AdapterTextRules]; the first half
 * runs them on the real adapter and the second on made-up adapters, one written correctly and then
 * reformatted, and the others each with one mistake planted.
 *
 * An unknown stored tombstone reason is now the store's to skip and report: the three databases hand the
 * word on the disk back as a `Tombstone.StoredRow`, and `pendingTombstones` maps it, skips it and reports
 * it. The rules below pin both halves, and each half has a made-up body the rule refuses.
 *
 * A cursor closed by `.use` and a stored word handed back as text are what the source says; that `use`
 * closes a cursor is Kotlin's rule, and what the platform does with an unclosed one is not run here.
 */
class AdapterTextQueriesTest {

    // ── the real adapter ─────────────────────────────────────────────────

    @Test
    fun `every rawQuery result in the adapter is consumed by use, so the cursor is closed`() {
        AdapterTextRules.checkCursorsClosed(AdapterStatements.adapterSource())
    }

    @Test
    fun `the three databases hand the stored reason back as text and the store skips and reports it`() {
        AdapterTextRules.checkUnknownReasonThrows(
            adapterSource = AdapterStatements.adapterSource(),
            jdbcSource = moduleSource("JdbcHistoryDatabase.kt"),
            inMemorySource = moduleSource("InMemoryHistoryDatabase.kt"),
            storeSource = storeSource(),
        )
    }

    @Test
    fun `the adapter's count of an empty table is 0`() {
        AdapterTextRules.checkCountFallsBackToZero(AdapterStatements.adapterSource())
    }

    // ── made-up adapters: cursors ────────────────────────────────────────

    @Test
    fun `cursors consumed by use are accepted, and so is the same code reformatted`() {
        val variants = mapOf(
            "as the adapter writes it" to adapter(),
            "with the use on the same line" to adapter(newest = "q.rawQuery(S, a).use { c -> c.x() }"),
            "with the call split over lines" to adapter(
                newest = "q\n            .rawQuery(\n                S,\n                a,\n            )\n" +
                    "            .use { c ->\n                c.x()\n            }",
            ),
            "with a space before the brace and after the dot" to
                adapter(newest = "q.rawQuery(S, a). use{ c -> c.x() }"),
            "with a comment between the call and the use" to
                adapter(newest = "q.rawQuery(S, a) // the cursor\n            .use { c -> c.x() }"),
        )
        for ((label, source) in variants) {
            try {
                AdapterTextRules.checkCursorsClosed(source)
            } catch (refused: AdapterShapeError) {
                throw AssertionError("a correct adapter was refused $label: ${refused.message}", refused)
            }
        }
    }

    @Test
    fun `a cursor consumed by let instead of use is refused, and the method is named`() {
        val source = adapter(ids = "q.rawQuery(S, a).let { c -> c.x() }")
        val refused = refusal { AdapterTextRules.checkCursorsClosed(source) }
        assertTrue(refused, refused.contains("not consumed by .use { } in idsCreatedBefore"))
        assertTrue(refused, !refused.contains("newest (") && !refused.contains("countTranscriptions ("))
    }

    @Test
    fun `a cursor that is never closed is refused`() {
        val refused = refusal { AdapterTextRules.checkCursorsClosed(adapter(count = "q.rawQuery(S, a).x()")) }
        assertTrue(refused, refused.contains("not consumed by .use { } in countTranscriptions"))
    }

    @Test
    fun `a cursor held in a variable and used later is refused, not passed`() {
        val refused = refusal {
            AdapterTextRules.checkCursorsClosed(adapter(newest = "val c = q.rawQuery(S, a)\n        c.use { it.x() }"))
        }
        assertTrue(refused, refused.contains("in newest"))
    }

    @Test
    fun `an adapter with no rawQuery in one of its reading methods is refused, so a scan of nothing cannot pass`() {
        val refused = refusal { AdapterTextRules.checkCursorsClosed(adapter(newest = "emptyList()")) }
        assertTrue(refused, refused.contains("found no rawQuery call in [newest]"))
        val nothing = refusal { AdapterTextRules.checkCursorsClosed("internal class A {\n    fun other() {}\n}\n") }
        assertTrue(nothing, nothing.contains("found no rawQuery call in"))
    }

    @Test
    fun `the word rawQuery in a comment or a string is not a call`() {
        val source = adapter(newest = "q.rawQuery(S, a).use { c -> c.x() } // q.rawQuery(S, a).let { }\n" +
            "        println(\"rawQuery(S)\")")
        AdapterTextRules.checkCursorsClosed(source)
    }

    // ── made-up adapters: an unknown stored reason ───────────────────────

    @Test
    fun `a database that hands the stored reason back as text is accepted, reformatted or not`() {
        val variants = mapOf(
            "as a database writes it" to db(),
            "with the row built over lines" to db(
                tombstones = "q.rawQuery(S, a).use { cursor ->\n" +
                    "                buildList {\n" +
                    "                    while (cursor.moveToNext()) {\n" +
                    "                        val id = cursor.getString(0)\n" +
                    "                        val deletedAt = cursor.getLong(1)\n" +
                    "                        val reason = cursor.getString(2)\n" +
                    "                        add(\n" +
                    "                            Tombstone.StoredRow(\n" +
                    "                                id = id,\n" +
                    "                                deletedAt = deletedAt,\n" +
                    "                                reason = reason,\n" +
                    "                            ),\n" +
                    "                        )\n" +
                    "                    }\n" +
                    "                }\n" +
                    "            }",
            ),
            "with a comment in the body" to db(
                tombstones = "q.rawQuery(S, a).use { cursor ->\n" +
                    "                // the stored word is not this layer's to interpret\n" +
                    "                buildList {\n" +
                    "                    while (cursor.moveToNext()) {\n" +
                    "                        add(Tombstone.StoredRow(cursor.getString(0), cursor.getLong(1), cursor.getString(2)))\n" +
                    "                    }\n" +
                    "                }\n" +
                    "            }",
            ),
            "with the word in a local named for the column" to db(
                tombstones = "q.rawQuery(S, a).use { cursor ->\n" +
                    "                buildList {\n" +
                    "                    while (cursor.moveToNext()) {\n" +
                    "                        val reason = cursor.getString(2)\n" +
                    "                        add(Tombstone.StoredRow(cursor.getString(0), cursor.getLong(1), reason))\n" +
                    "                    }\n" +
                    "                }\n" +
                    "            }",
            ),
        )
        for ((label, source) in variants) {
            try {
                AdapterTextRules.checkUnknownReasonThrows(source, db(), db(), store())
            } catch (refused: AdapterShapeError) {
                throw AssertionError("a correct database was refused $label: ${refused.message}", refused)
            }
        }
    }

    @Test
    fun `a database that still throws MappingFailure for an unknown reason is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                db(
                    tombstones = "q.rawQuery(S, a).use { cursor ->\n" +
                        "                buildList {\n" +
                        "                    while (cursor.moveToNext()) {\n" +
                        "                        val stored = cursor.getString(2)\n" +
                        "                        val reason = Tombstone.Reason.fromStored(stored)\n" +
                        "                            ?: throw MappingFailure(\"unknown '\$stored'\")\n" +
                        "                        add(Tombstone(reason))\n" +
                        "                    }\n" +
                        "                }\n" +
                        "            }",
                ),
                db(),
                db(),
                store(),
            )
        }
        assertTrue(refused, refused.contains("SqliteHistoryDatabase still throws"))
    }

    @Test
    fun `a database that still maps the reason itself is refused, because the mapping belongs to the store`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                db(
                    tombstones = "q.rawQuery(S, a).use { cursor ->\n" +
                        "                buildList {\n" +
                        "                    while (cursor.moveToNext()) {\n" +
                        "                        val stored = cursor.getString(2)\n" +
                        "                        val reason = Tombstone.Reason.fromStored(stored)!!\n" +
                        "                        add(Tombstone.StoredRow(cursor.getString(0), cursor.getLong(1), reason.stored))\n" +
                        "                    }\n" +
                        "                }\n" +
                        "            }",
                ),
                db(),
                db(),
                store(),
            )
        }
        assertTrue(refused, refused.contains("still calls fromStored"))
    }

    // ── made-up stores: an unmappable tombstone row ─────────────────────

    @Test
    fun `a store that maps, skips and reports an unmappable tombstone is accepted, reformatted or not`() {
        val variants = mapOf(
            "as the store writes it" to store(),
            "with the report on the next line" to store(
                pending = "database.newestTombstones(limit).mapNotNull { row ->\n" +
                    "                if (row.toTombstoneOrNull() == null)\n" +
                    "                {\n" +
                    "                    report(\"history tombstone \${row.id} has an unknown reason '\${row.reason}'; it stays stored\")\n" +
                    "                    null\n" +
                    "                }\n" +
                    "                else\n" +
                    "                {\n" +
                    "                    row.toTombstone()\n" +
                    "                }\n" +
                    "            }",
            ),
            "with a comment in the branch" to store(
                pending = "database.newestTombstones(limit).mapNotNull { row ->\n" +
                    "                if (row.toTombstoneOrNull() == null) {\n" +
                    "                    // a tombstone dropped on read is a delete the server never learns about\n" +
                    "                    report(\"history tombstone \${row.id} has an unknown reason '\${row.reason}'; it stays stored\")\n" +
                    "                    null\n" +
                    "                } else {\n" +
                    "                    row.toTombstone()\n" +
                    "                }\n" +
                    "            }",
            ),
        )
        for ((label, source) in variants) {
            try {
                AdapterTextRules.checkUnknownReasonThrows(db(), db(), db(), source)
            } catch (refused: AdapterShapeError) {
                throw AssertionError("a correct store was refused $label: ${refused.message}", refused)
            }
        }
    }

    @Test
    fun `a store that throws instead of reporting an unmappable tombstone is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                db(),
                db(),
                db(),
                store(
                    pending = "database.newestTombstones(limit).mapNotNull { row ->\n" +
                        "                if (row.toTombstoneOrNull() == null) throw MappingFailure(\"unknown '\${row.reason}'\")\n" +
                        "                else row.toTombstone()\n" +
                        "            }",
                ),
            )
        }
        assertTrue(refused, refused.contains("throws MappingFailure"))
    }

    @Test
    fun `a store that skips an unmappable tombstone without reporting it is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                db(),
                db(),
                db(),
                store(
                    pending = "database.newestTombstones(limit).mapNotNull { row ->\n" +
                        "                if (row.toTombstoneOrNull() == null) {\n" +
                        "                    null\n" +
                        "                } else {\n" +
                        "                    row.toTombstone()\n" +
                        "                }\n" +
                        "            }",
                ),
            )
        }
        assertTrue(refused, refused.contains("does not report an unmappable row"))
    }

    @Test
    fun `a store that reports an unmappable tombstone but does not skip it is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                db(),
                db(),
                db(),
                store(
                    pending = "database.newestTombstones(limit).mapNotNull { row ->\n" +
                        "                if (row.toTombstoneOrNull() == null) {\n" +
                        "                    report(\"history tombstone \${row.id} has an unknown reason '\${row.reason}'; it stays stored\")\n" +
                        "                    row\n" +
                        "                } else {\n" +
                        "                    row.toTombstone()\n" +
                        "                }\n" +
                        "            }",
                ),
            )
        }
        assertTrue(refused, refused.contains("does not skip it"))
    }

    @Test
    fun `a store that deletes an unmappable tombstone instead of leaving it stored is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                db(),
                db(),
                db(),
                store(
                    pending = "database.newestTombstones(limit).mapNotNull { row ->\n" +
                        "                if (row.toTombstoneOrNull() == null) {\n" +
                        "                    deleteTombstonesRecordedBefore(0)\n" +
                        "                    report(\"history tombstone \${row.id} has an unknown reason '\${row.reason}'; it stays stored\")\n" +
                        "                    null\n" +
                        "                } else {\n" +
                        "                    row.toTombstone()\n" +
                        "                }\n" +
                        "            }",
                ),
            )
        }
        assertTrue(refused, refused.contains("deletes"))
    }

    @Test
    fun `a report that does not name the row id and the stored reason is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                db(),
                db(),
                db(),
                store(
                    pending = "database.newestTombstones(limit).mapNotNull { row ->\n" +
                        "                if (row.toTombstoneOrNull() == null) {\n" +
                        "                    report(\"an unmappable tombstone was skipped\")\n" +
                        "                    null\n" +
                        "                } else {\n" +
                        "                    row.toTombstone()\n" +
                        "                }\n" +
                        "            }",
                ),
            )
        }
        assertTrue(refused, refused.contains("expected the row id and the stored reason"))
    }

    @Test
    fun `a store with no mapping at all is refused, so a scan of nothing cannot pass`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                db(),
                db(),
                db(),
                store(pending = "database.newestTombstones(limit).map { row -> row.toTombstone() }"),
            )
        }
        assertTrue(refused, refused.contains("does not map its stored rows with mapNotNull"))
    }

    // ── made-up adapters: the count ──────────────────────────────────────

    @Test
    fun `a count that falls back to 0 is accepted, reformatted or not`() {
        val variants = mapOf(
            "as the adapter writes it" to adapter(),
            "over two lines" to adapter(count = "q.rawQuery(S, a).use { c ->\n            if (c.moveToNext())\n" +
                "                c.getInt(0)\n            else\n                0\n        }"),
            "with braces round both branches" to adapter(
                count = "q.rawQuery(S, a).use { c ->\n            if (c.moveToNext()) {\n" +
                    "                c.getInt(0)\n            } else {\n                0\n            }\n        }",
            ),
            "with another name for the cursor" to
                adapter(count = "q.rawQuery(S, a).use { k -> if (k.moveToNext()) k.getInt(0) else 0 }"),
        )
        for ((label, source) in variants) {
            try {
                AdapterTextRules.checkCountFallsBackToZero(source)
            } catch (refused: AdapterShapeError) {
                throw AssertionError("a correct adapter was refused $label: ${refused.message}", refused)
            }
        }
    }

    @Test
    fun `a count that falls back to another number is refused`() {
        for (other in listOf("-1", "1", "null", "0L", "01", "0.5", "count", "{ 1 }", "{ 0; 1 }")) {
            val count = "q.rawQuery(S, a).use { c -> if (c.moveToNext()) c.getInt(0) else $other }"
            val refused = refusal { AdapterTextRules.checkCountFallsBackToZero(adapter(count = count)) }
            assertTrue("$other: $refused", refused.contains("is not `if (c.moveToNext()) c.getInt(0) else 0`"))
        }
    }

    @Test
    fun `a count read from another column, or without asking for a row, is refused`() {
        for (count in listOf(
            "q.rawQuery(S, a).use { c -> if (c.moveToNext()) c.getInt(1) else 0 }",
            "q.rawQuery(S, a).use { c -> c.getInt(0) }",
            "q.rawQuery(S, a).use { c -> if (c.moveToNext()) d.getInt(0) else 0 }",
        )) {
            val refused = refusal { AdapterTextRules.checkCountFallsBackToZero(adapter(count = count)) }
            assertTrue(refused, refused.contains("is not `if (c.moveToNext()) c.getInt(0) else 0`"))
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun refusal(action: () -> Unit): String =
        assertThrows(AdapterShapeError::class.java) { action() }.message.orEmpty()

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

    /** An adapter with the four reading methods, each written as the real one is, one piece replaced at a time. */
    private fun adapter(
        newest: String = "q.rawQuery(S, a).use { c -> c.x() }",
        ids: String = "q.rawQuery(S, a)\n            .use { c -> c.x() }",
        tombstones: String = "q.rawQuery(S, a).use { cursor ->\n" +
            "                buildList {\n" +
            "                    while (cursor.moveToNext()) {\n" +
            "                        val id = cursor.getString(0)\n" +
            "                        val deletedAt = cursor.getLong(1)\n" +
            "                        val reason = cursor.getString(2)\n" +
            "                        add(Tombstone.StoredRow(id = id, deletedAt = deletedAt, reason = reason))\n" +
            "                    }\n" +
            "                }\n" +
            "            }",
        count: String = "q.rawQuery(S, a).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }",
    ): String =
        "internal class Adapter {\n" +
            "    override fun newest(limit: Int): List<Row> =\n        $newest\n\n" +
            "    override fun idsCreatedBefore(cutoff: Long): List<String> =\n        $ids\n\n" +
            "    override fun newestTombstones(limit: Int): List<Tombstone.StoredRow> =\n        $tombstones\n\n" +
            "    override fun countTranscriptions(): Int =\n        $count\n\n" +
            "    override fun close() = helper.close()\n" +
            "}\n"

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
            "        return $pending\n" +
            "    }\n" +
            "}\n"
}
