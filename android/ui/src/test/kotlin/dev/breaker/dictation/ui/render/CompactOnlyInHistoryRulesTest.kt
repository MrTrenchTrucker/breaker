package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact flag is set in one place only: the copy, toggle and delete buttons of a History entry row.
 * The rules read the code of the real main sources, with comments and strings blanked. Each rule runs
 * first on samples made from the real text with one change, so a wrong change is reported with its words
 * and the real text is quiet. A file that cannot be found is a violation, never a pass.
 */
class CompactOnlyInHistoryRulesTest {
    private val HISTORY = "screen/history/HistoryScreen.kt"
    private val HOME = "screen/home/HomeScreen.kt"
    private val RENDERER = "render/ScreenRenderer.kt"
    private val CARD = "screen/history/TranscriptCard.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("compact only in history: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("compact only in history: $what must not be reported", emptyList<String>(), problems)

    /** The text of the real file at [path], read from the main tree. */
    private fun realText(path: String): String {
        val text = MAIN_SOURCES.toMap()[path]
        assertTrue("compact only in history: $path was not read from the main tree", text != null)
        return text ?: ""
    }

    /** The main sources with the file at [path] given [text] in place of its real text. */
    private fun withFile(path: String, text: String): Map<String, String> = MAIN_SOURCES.toMap() + (path to text)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun onceIn(text: String, old: String, new: String): String {
        assertTrue("compact only in history: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    // ---- The flag is set once, and only in the History entry rows ----

    private val FLAG_ON = Regex("""\bcompact\s*=\s*true\b""")
    private val ROW_CALL_FLAG = Regex("""row\.id\),\s+compact = true,\s+\)""")

    private fun flagProblems(files: Map<String, String>): List<String> {
        val setIn = files.mapValues { (_, text) -> FLAG_ON.findAll(codeOf(text)).count() }.filterValues { it > 0 }
        val total = setIn.values.sum()
        val history = codeOf(files[HISTORY] ?: "")
        return listOfNotNull(
            "compact = true must be set exactly once in the main sources, found $total".takeUnless { total == 1 },
            "compact = true is set outside the History entry rows, in ${setIn.keys - HISTORY}".takeUnless { setIn.keys.all { it == HISTORY } },
            "compact = true is not in the transcriptCard call of the History rows".takeUnless { ROW_CALL_FLAG.containsMatchIn(history) },
        )
    }

    /** A failure means the flag is set twice, or set on a screen other than the History rows. */
    @Test
    fun `the compact flag is set exactly once and only in the History entry rows`() {
        val historyOff = onceIn(realText(HISTORY), "compact = true,", "compact = false,")
        assertFires("the History row flag removed", flagProblems(withFile(HISTORY, historyOff)), "exactly once")
        val homeOn = onceIn(realText(HOME), "deleteIntent = null,", "deleteIntent = null,\n            compact = true,")
        assertFires("the Home card flag added", flagProblems(withFile(HOME, homeOn)), "outside the History entry rows")
        assertQuiet("the real main sources", flagProblems(MAIN_SOURCES.toMap()))
    }

    // ---- The renderer hands the flag to the four kit views, and not to the text row ----

    private val NODE_FLAG = Regex("""\bnode\.compact\b""")
    private val VIEWS = listOf(
        "Button3dView" to Regex("""\bButton3dView\(context, node\.text, theme, node\.compact\)"""),
        "NeutralButtonView" to Regex("""\bNeutralButtonView\(context, node\.text, theme, node\.compact\)"""),
        "DeleteButtonView" to Regex("""\bDeleteButtonView\(context, node\.text, theme, node\.compact\)"""),
        "OutlinedButtonView" to Regex("""\bOutlinedButtonView\(context, node\.text, theme, node\.compact\)"""),
    )

    private fun rendererProblems(text: String): List<String> {
        val code = codeOf(text)
        val passes = NODE_FLAG.findAll(code).count()
        return listOfNotNull("the renderer passes node.compact $passes times, not four".takeUnless { passes == 4 }) +
            VIEWS.mapNotNull { (view, rule) ->
                "the renderer does not pass node.compact to $view".takeUnless { rule.findAll(code).count() == 1 }
            }
    }

    /** A failure means a kit view does not get the flag, or the text row gets it. */
    @Test
    fun `the renderer passes the flag to the four kit views and not to the text row`() {
        val neutralDropped = onceIn(
            realText(RENDERER),
            "NeutralButtonView(context, node.text, theme, node.compact)",
            "NeutralButtonView(context, node.text, theme)",
        )
        assertFires("the neutral view without the flag", rendererProblems(neutralDropped), "does not pass node.compact to NeutralButtonView")
        val textGiven = onceIn(
            realText(RENDERER),
            "TextRowView(context, node.text, theme)",
            "TextRowView(context, node.text, theme, node.compact)",
        )
        assertFires("the text row given the flag", rendererProblems(textGiven), "not four")
        assertQuiet("the real renderer", rendererProblems(realText(RENDERER)))
    }

    // ---- The shared card declares the defaulted parameter, and its three buttons read it ----

    private val DECLARES = Regex("""deleteIntent: ScreenIntent\? = null,\s+compact: Boolean = false,\s+\)""")
    private val ACTION_READS = Regex("""compact = compact,""")

    private fun cardProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the card does not declare the defaulted compact parameter after deleteIntent".takeUnless { DECLARES.containsMatchIn(code) },
            "the card's Actions do not all read compact, expected three".takeUnless { ACTION_READS.findAll(code).count() == 3 },
        )
    }

    /** A failure means the card does not take the parameter, or one of its buttons ignores it. */
    @Test
    fun `the card declares the defaulted compact parameter and its three actions read it`() {
        val undeclared = onceIn(realText(CARD), "    compact: Boolean = false,\n", "")
        assertFires("the parameter not declared", cardProblems(undeclared), "does not declare")
        val deleteIgnores = onceIn(
            realText(CARD),
            "kit = KitLook.DANGER,\n                        compact = compact,",
            "kit = KitLook.DANGER,\n                        compact = false,",
        )
        assertFires("the delete button ignores the parameter", cardProblems(deleteIgnores), "expected three")
        assertQuiet("the real card", cardProblems(realText(CARD)))
    }
}
