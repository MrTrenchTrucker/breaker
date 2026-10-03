package dev.breaker.dictation.history

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone's adapter does with a cursor and with a stored value it does not know, read as text.
 *
 * `SqliteHistoryDatabase` runs in no JVM test, so nothing else notices a cursor that is never closed, a row
 * with an unknown stored reason that is skipped instead of reported, or a count that answers something other
 * than 0 for an empty table. The rules are in [AdapterTextRules]; the first half runs them on the real adapter
 * and the second on made-up adapters, one written correctly and then reformatted, and the others each with
 * one mistake planted.
 *
 * A cursor closed by `.use` and a thrown `MappingFailure` are what the source says; that `use` closes a cursor
 * is Kotlin's rule, and what the platform does with an unclosed one is not run here.
 */
class AdapterTextQueriesTest {

    // ── the real adapter ─────────────────────────────────────────────────

    @Test
    fun `every rawQuery result in the adapter is consumed by use, so the cursor is closed`() {
        AdapterTextRules.checkCursorsClosed(AdapterStatements.adapterSource())
    }

    @Test
    fun `the adapter throws MappingFailure for an unknown stored tombstone reason and never skips the row`() {
        AdapterTextRules.checkUnknownReasonThrows(AdapterStatements.adapterSource())
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

    // ── made-up adapters: the unknown reason ─────────────────────────────

    @Test
    fun `a branch that throws MappingFailure is accepted, reformatted or not`() {
        val variants = mapOf(
            "as the adapter writes it" to adapter(),
            "with the brace on the next line" to adapter(unknown = "if (reason == null)\n                {\n" +
                "                    throw MappingFailure(\"unknown '\$stored'\")\n                }"),
            "with a comment in the branch" to adapter(unknown = "if (reason == null) {\n" +
                "                    // continue would hide it\n" +
                "                    throw MappingFailure(\"x\")\n                }"),
            "with a qualified read" to adapter(read = "val reason = Tombstone.Reason . fromStored ( stored )"),
            "with the word continue in a string" to adapter(unknown = "if (reason == null) {\n" +
                "                    throw MappingFailure(\"do not continue\")\n                }"),
        )
        for ((label, source) in variants) {
            try {
                AdapterTextRules.checkUnknownReasonThrows(source)
            } catch (refused: AdapterShapeError) {
                throw AssertionError("a correct adapter was refused $label: ${refused.message}", refused)
            }
        }
    }

    @Test
    fun `an unknown reason that skips the row with continue is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(adapter(unknown = "if (reason == null) {\n continue\n }"))
        }
        assertTrue(refused, refused.contains("does not throw MappingFailure"))
    }

    @Test
    fun `an unknown reason that throws and then skips, or returns, is refused for the skip`() {
        val skipping = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                adapter(
                    unknown = "if (reason == null) {\n if (stored == \"\") continue\n" +
                        " throw MappingFailure(\"x\")\n }",
                ),
            )
        }
        assertTrue(skipping, skipping.contains("holds `continue`"))
        val returning = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                adapter(
                    unknown = "if (reason == null) {\n if (stored == \"\") return emptyList()\n" +
                        " throw MappingFailure(\"x\")\n }",
                ),
            )
        }
        assertTrue(returning, returning.contains("holds `return`"))
    }

    @Test
    fun `an unknown reason that is given a default is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(adapter(unknown = "if (reason == null) {\n reason2 = USER\n }"))
        }
        assertTrue(refused, refused.contains("does not throw MappingFailure"))
    }

    @Test
    fun `an unknown reason that throws another exception is refused`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                adapter(unknown = "if (reason == null) {\n throw IllegalStateException(\"x\")\n }"),
            )
        }
        assertTrue(refused, refused.contains("does not throw MappingFailure"))
    }

    @Test
    fun `an adapter with no test of the read reason is refused`() {
        val noTest = refusal { AdapterTextRules.checkUnknownReasonThrows(adapter(unknown = "reason.hashCode()")) }
        assertTrue(noTest, noTest.contains("has no `if (reason == null) { ... }` after the read"))
        val noRead = refusal { AdapterTextRules.checkUnknownReasonThrows(adapter(read = "val reason = stored")) }
        assertTrue(noRead, noRead.contains("has no `val x = ...fromStored(`"))
    }

    @Test
    fun `the throw in a comment does not count as the branch's throw`() {
        val refused = refusal {
            AdapterTextRules.checkUnknownReasonThrows(
                adapter(unknown = "if (reason == null) {\n // throw MappingFailure(\"x\")\n reason2 = USER\n }"),
            )
        }
        assertTrue(refused, refused.contains("does not throw MappingFailure"))
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

    /** An adapter with the four reading methods, each written as the real one is, one piece replaced at a time. */
    private fun adapter(
        newest: String = "q.rawQuery(S, a).use { c -> c.x() }",
        ids: String = "q.rawQuery(S, a)\n            .use { c -> c.x() }",
        read: String = "val reason = Tombstone.Reason.fromStored(stored)",
        unknown: String = "if (reason == null) {\n                    throw MappingFailure(\"unknown '\$stored'\")\n" +
            "                }",
        count: String = "q.rawQuery(S, a).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }",
    ): String =
        "internal class Adapter {\n" +
            "    override fun newest(limit: Int): List<Row> =\n        $newest\n\n" +
            "    override fun idsCreatedBefore(cutoff: Long): List<String> =\n        $ids\n\n" +
            "    override fun newestTombstones(limit: Int): List<Tombstone> =\n" +
            "        q.rawQuery(S, a).use { cursor ->\n" +
            "            buildList {\n" +
            "                while (cursor.moveToNext()) {\n" +
            "                    val stored = cursor.getString(2)\n" +
            "                    $read\n" +
            "                    $unknown\n" +
            "                    add(Tombstone(reason))\n" +
            "                }\n" +
            "            }\n" +
            "        }\n\n" +
            "    override fun countTranscriptions(): Int =\n        $count\n\n" +
            "    override fun close() = helper.close()\n" +
            "}\n"
}
