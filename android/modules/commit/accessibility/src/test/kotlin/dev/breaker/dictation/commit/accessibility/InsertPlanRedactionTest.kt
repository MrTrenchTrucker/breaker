package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Neither the field's text nor the dictated text may show in any printed form of the
 * insert's types, and the insert never throws, so no exception message can carry text either.
 *
 * A marker string stands in for the user's text, both as the field's text and as the
 * dictated text, and must appear in no `toString()`.
 */
internal class InsertPlanRedactionTest {

    private val marker: String = "SECRET-DICTATION-7f3a"

    /** Inputs at the edges: empty, huge numbers, lone surrogates, a limit of zero, the marker. */
    private fun extremeRows(): List<Pair<FieldState, String>> = listOf(
        fieldState() to "",
        fieldState() to "x",
        fieldState("", 0, 0) to "",
        fieldState("", 0, 0) to "x",
        fieldState("", Int.MIN_VALUE, Int.MIN_VALUE) to "x",
        fieldState("", Int.MAX_VALUE, Int.MAX_VALUE) to "x",
        fieldState("", Int.MIN_VALUE, Int.MAX_VALUE) to "x",
        fieldState("", Int.MAX_VALUE, Int.MIN_VALUE) to "x",
        fieldState("abc", Int.MIN_VALUE, Int.MAX_VALUE) to "x",
        fieldState("abc", Int.MAX_VALUE, Int.MIN_VALUE) to "x",
        fieldState("abc", Int.MAX_VALUE, Int.MAX_VALUE) to "x",
        fieldState("abc", Int.MIN_VALUE, Int.MIN_VALUE) to "x",
        fieldState("abc", Int.MAX_VALUE, 0) to "x",
        fieldState("abc", 0, Int.MAX_VALUE) to "x",
        fieldState("abc", Int.MIN_VALUE, 0) to "x",
        fieldState("abc", 0, Int.MIN_VALUE) to "x",
        fieldState("abc", -1, Int.MAX_VALUE) to "x",
        fieldState("\uD83D", 0, 1) to "\uDE00",
        fieldState("\uD83D", 1, 1) to "\uD83D",
        fieldState("\uDE00", 1, 1) to "\uDE00",
        fieldState("\uD83D\uD83D\uDE00\uDE00", 2, 2) to "x",
        fieldState("\uD83D\uDE00", 1, 1) to "\uD83D",
        fieldState("\uD83D\uDE00", 1, 1) to "\uDE00\uD83D",
        fieldState("", 0, 0, maxTextLength = 0) to "x",
        fieldState("abc", 0, 3, maxTextLength = 0) to "x",
        fieldState("abc", 0, 3, maxTextLength = Int.MIN_VALUE) to "x",
        fieldState("abc", 0, 3, maxTextLength = Int.MAX_VALUE) to "x",
        fieldState(marker, 0, 0) to marker,
        fieldState(marker, Int.MAX_VALUE, Int.MIN_VALUE, isShowingHint = true) to marker,
        fieldState(marker, 3, 9, isPassword = true) to marker,
        fieldState(marker, 5, 5, maxTextLength = 5) to marker,
        fieldState("abc", 1, 2, isEditable = false) to "x",
        fieldState("abc", 1, 2, isEnabled = false) to "x",
        fieldState(
            "abc",
            1,
            2,
            isShowingHint = true,
            isPassword = true,
            isEditable = false,
            isEnabled = false,
            maxTextLength = 0,
        ) to "",
    )

    @Test
    fun `the field state prints a fixed text and not its own text`() {
        val states: List<FieldState> = listOf(
            fieldState(marker, 0, 0),
            fieldState(marker, -1, -1, isShowingHint = true),
            fieldState(marker, 0, 0, isPassword = true),
            fieldState(marker, 0, 0, isEditable = false),
            fieldState(marker, 0, 0, isEnabled = false),
            fieldState(marker, 0, 0, maxTextLength = 3),
            fieldState(),
            FieldState(marker, 0, 1, false, false, true, true, -1),
        )
        for (state in states) {
            assertEquals("commit/accessibility: a field state must print a fixed text", "FieldState(redacted)", state.toString())
        }
    }

    @Test
    fun `an insert prints a fixed text and not the new text`() {
        val outcome: InsertOutcome = InsertPlan.plan(fieldState(marker, 0, 0), marker)

        // The outcome really holds the marker, so the fixed text below is a choice and not an accident.
        assertTrue(
            "commit/accessibility: the insert must carry the marker for this check to mean anything",
            outcome is Inserted && outcome.newText.contains(marker),
        )
        assertEquals("commit/accessibility: an insert must print a fixed text", "Inserted(redacted)", outcome.toString())
        assertEquals(
            "commit/accessibility: an insert built directly must print a fixed text",
            "Inserted(redacted)",
            Inserted(marker, 7).toString(),
        )
    }

    @Test
    fun `each refusal prints its fixed name and nothing else`() {
        val expected: Map<Refusal, String> = mapOf(
            Refusal.PASSWORD to "Refused(PASSWORD)",
            Refusal.NOT_EDITABLE to "Refused(NOT_EDITABLE)",
            Refusal.NOT_ENABLED to "Refused(NOT_ENABLED)",
            Refusal.EMPTY_TEXT to "Refused(EMPTY_TEXT)",
            Refusal.TOO_LONG to "Refused(TOO_LONG)",
        )
        assertEquals("commit/accessibility: every refusal reason needs an expected text", Refusal.entries.toSet(), expected.keys)
        for ((reason, text) in expected) {
            assertEquals("commit/accessibility: a refusal must print its fixed name", text, Refused(reason).toString())
        }
    }

    @Test
    fun `refusals of fields that hold the marker print only the reason`() {
        val cases: List<Pair<Pair<FieldState, String>, String>> = listOf(
            (fieldState(marker, 0, 0, isPassword = true) to marker) to "Refused(PASSWORD)",
            (fieldState(marker, 0, 0, isEditable = false) to marker) to "Refused(NOT_EDITABLE)",
            (fieldState(marker, 0, 0, isEnabled = false) to marker) to "Refused(NOT_ENABLED)",
            (fieldState(marker, 0, 0) to "") to "Refused(EMPTY_TEXT)",
            (fieldState(marker, 0, 0, maxTextLength = 3) to marker) to "Refused(TOO_LONG)",
        )
        for ((input, expectedPrint) in cases) {
            val outcome: InsertOutcome = InsertPlan.plan(input.first, input.second)
            assertEquals("commit/accessibility: a refusal must print only its reason", expectedPrint, outcome.toString())
            assertFalse("commit/accessibility: a refusal must not carry the marker", outcome.toString().contains(marker))
        }
    }

    @Test
    fun `no refusal name holds the marker`() {
        val names: List<String> = Refusal.entries.map { it.name }

        assertEquals(
            "commit/accessibility: the refusal reasons must be exactly the five fixed names",
            listOf("PASSWORD", "NOT_EDITABLE", "NOT_ENABLED", "EMPTY_TEXT", "TOO_LONG"),
            names,
        )
        for (name in names) {
            assertFalse("commit/accessibility: a refusal name must not hold the marker", name.contains(marker))
        }
    }

    @Test
    fun `no printed outcome across the table holds the marker`() {
        for ((index, row) in extremeRows().withIndex()) {
            val printed: String = InsertPlan.plan(row.first, row.second).toString()
            assertFalse(
                "commit/accessibility: the printed outcome of table row " + index + " must not hold the marker",
                printed.contains(marker),
            )
            assertTrue(
                "commit/accessibility: the printed outcome of table row " + index + " must be a fixed form",
                printed == "Inserted(redacted)" || printed.startsWith("Refused(") && printed.endsWith(")"),
            )
        }
    }

    @Test
    fun `plan never throws across extreme inputs`() {
        val rows: List<Pair<FieldState, String>> = extremeRows()
        assertTrue("commit/accessibility: the table of extreme inputs must not be empty", rows.size > 20)
        for ((index, row) in rows.withIndex()) {
            try {
                val dictated: String = row.second
                val outcome: InsertOutcome = InsertPlan.plan(row.first, dictated)
                if (outcome is Inserted) {
                    // The cursor sits right after the dictated text, inside the new text, and the limit holds.
                    val start: Int = outcome.cursor - dictated.length
                    assertTrue(
                        "commit/accessibility: the cursor of table row " + index + " must lie after the dictated text and inside the new text",
                        start >= 0 && outcome.cursor <= outcome.newText.length,
                    )
                    assertEquals(
                        "commit/accessibility: the dictated text of table row " + index + " must sit just before the cursor",
                        dictated,
                        outcome.newText.substring(start, outcome.cursor),
                    )
                    val limit: Int = row.first.maxTextLength
                    assertTrue(
                        "commit/accessibility: the new text of table row " + index + " must not be longer than the limit",
                        limit < 0 || outcome.newText.length <= limit,
                    )
                }
            } catch (failure: RuntimeException) {
                fail("commit/accessibility: plan threw " + failure.javaClass.name + " on table row " + index)
            }
        }
    }
}
