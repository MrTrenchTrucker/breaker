package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact width of the kit's buttons, checked on source text the way the other kit rules are checked.
 * Each measured button passes its compact flag to the width helper, the helper passes it to the geometry,
 * and the geometry picks the compact floor by the flag while it keeps the label's own growth. Each rule
 * runs first on short made-up samples, so a wrong one is reported with the right words and a correct one
 * is quiet, and only then on the real files read from the main tree. A file that cannot be found is a
 * violation, never a pass. Comments and string literals are blanked before a rule reads the code.
 */
class CompactButtonRulesTest {

    private val BUTTON = "render/controls/Button3dView.kt"
    private val NEUTRAL = "render/controls/NeutralButtonView.kt"
    private val OUTLINED = "render/controls/OutlinedButtonView.kt"
    private val DELETE = "render/controls/DeleteButtonView.kt"
    private val FULL_WIDTH = "render/controls/FullWidthButtonView.kt"
    private val GEOMETRY = "controls/ControlsGeometry.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("render: compact: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: compact: $what must not be reported", emptyList<String>(), problems)

    /** The text of the real file at [path], read from the main tree. */
    private fun realText(path: String): String {
        val text = MAIN_SOURCES.toMap()[path]
        assertTrue("render: compact: $path was not read from the main tree", text != null)
        return text ?: ""
    }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun onceIn(text: String, old: String, new: String): String {
        assertTrue("render: compact: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    // ---- The measured views pass the flag to the width helper, and take it as a parameter ----

    private val MEASURE_WITH_FLAG = Regex("""resolveSize\(\s*buttonWidthPx\(textPaint,\s*label,\s*density,\s*compact\s*\),\s*widthMeasureSpec\)""")
    private val LABEL_WITH_FLAG = Regex("""fun labelWidthPx\(\): Int = buttonWidthPx\(textPaint,\s*label,\s*density,\s*compact\)""")
    private val TAKES_FLAG = Regex("""\bcompact: Boolean = false,""")
    private val PASSES_FLAG_UP = Regex("""\) : Button3dView\(context,\s*label,\s*theme,\s*compact\) \{""")

    private fun measureProblems(text: String): List<String> =
        listOfNotNull("the width is not measured with the compact flag".takeUnless { MEASURE_WITH_FLAG.containsMatchIn(codeOf(text)) })

    private fun mainButtonProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the width is not measured with the compact flag".takeUnless { MEASURE_WITH_FLAG.containsMatchIn(code) },
            "the label width does not pass the compact flag".takeUnless { LABEL_WITH_FLAG.containsMatchIn(code) },
            "the main button does not take the compact flag".takeUnless { TAKES_FLAG.containsMatchIn(code) },
        )
    }

    private fun subclassProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the view does not take the compact flag".takeUnless { TAKES_FLAG.containsMatchIn(code) },
            "the view does not pass the compact flag to the main button".takeUnless { PASSES_FLAG_UP.containsMatchIn(code) },
        )
    }

    /** A failure means a measured button drops the compact flag, so a History row is measured at the default floor. */
    @Test
    fun `every measured button passes the compact flag to the width helper`() {
        val measure = "            resolveSize(buttonWidthPx(textPaint, label, density, compact), widthMeasureSpec),\n"
        assertFires("the flag dropped from the width call", measureProblems(measure.replace(", compact)", ")")), "compact flag")
        assertFires(
            "the width at the floor",
            measureProblems(measure.replace("buttonWidthPx(textPaint, label, density, compact)", "touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)")),
            "compact flag",
        )
        assertQuiet("the flag passed to the width helper", measureProblems(measure))
        assertFires(
            "the neutral button with the flag dropped",
            measureProblems(onceIn(realText(NEUTRAL), "buttonWidthPx(textPaint, label, density, compact), widthMeasureSpec)", "buttonWidthPx(textPaint, label, density), widthMeasureSpec)")),
            "compact flag",
        )
        assertFires(
            "the outlined button with the flag dropped",
            measureProblems(onceIn(realText(OUTLINED), "buttonWidthPx(textPaint, label, density, compact), widthMeasureSpec)", "buttonWidthPx(textPaint, label, density), widthMeasureSpec)")),
            "compact flag",
        )
        assertFires(
            "the main button label width without the flag",
            mainButtonProblems(onceIn(realText(BUTTON), "fun labelWidthPx(): Int = buttonWidthPx(textPaint, label, density, compact)", "fun labelWidthPx(): Int = buttonWidthPx(textPaint, label, density)")),
            "does not pass the compact flag",
        )
        assertFires(
            "the main button without its own flag",
            mainButtonProblems(onceIn(realText(BUTTON), "private val compact: Boolean = false,", "")),
            "does not take the compact flag",
        )
        assertQuiet("the real neutral button", measureProblems(realText(NEUTRAL)))
        assertQuiet("the real outlined button", measureProblems(realText(OUTLINED)))
        assertQuiet("the real main button", mainButtonProblems(realText(BUTTON)))
    }

    /** A failure means a destructive or full width button does not hand the flag to the main button. */
    @Test
    fun `the subclass buttons take the flag and pass it to the main button`() {
        assertFires(
            "the flag not passed up",
            subclassProblems(onceIn(realText(DELETE), ") : Button3dView(context, label, theme, compact) {", ") : Button3dView(context, label, theme) {")),
            "does not pass",
        )
        assertFires(
            "the flag not taken",
            subclassProblems(onceIn(realText(FULL_WIDTH), "    compact: Boolean = false,\n", "")),
            "does not take",
        )
        assertQuiet("the real destructive button", subclassProblems(realText(DELETE)))
        assertQuiet("the real full-width button", subclassProblems(realText(FULL_WIDTH)))
    }

    // ---- The geometry: one compact constant, the floor chosen by the flag, the label growth kept ----

    private val CONSTANT = Regex("""const val BUTTON_COMPACT_MIN_WIDTH_DP: Float = 140f / 1\.5f\b""")
    private val FLOOR_BY_FLAG = Regex("""if \(compact\) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP\b""")
    private val GROWTH_KEPT = Regex("""labelWidthDp \+ 2f \* BUTTON_SIDE_DP\)""")

    private fun geometryProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the compact floor is not held exactly once as 140f / 1.5f".takeUnless { CONSTANT.findAll(code).count() == 1 },
            "the floor is not chosen by the compact flag".takeUnless { FLOOR_BY_FLAG.containsMatchIn(code) },
            "the label growth is not kept past the floor".takeUnless { GROWTH_KEPT.containsMatchIn(code) },
        )
    }

    /** A failure means the compact floor is not held once, the flag does not choose it, or a long label stops growing the button. */
    @Test
    fun `the geometry holds the compact floor once and chooses it by the flag`() {
        val geometry = "    const val BUTTON_COMPACT_MIN_WIDTH_DP: Float = 140f / 1.5f\n" +
            "    fun buttonWidth(labelWidthDp: Float, compact: Boolean = false): Float =\n" +
            "        maxOf(if (compact) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP, labelWidthDp + 2f * BUTTON_SIDE_DP)\n"
        assertFires(
            "the floor ignores the flag",
            geometryProblems(geometry.replace("if (compact) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP", "BUTTON_MIN_WIDTH_DP")),
            "not chosen by the compact flag",
        )
        assertFires(
            "the compact floor used for every button",
            geometryProblems(geometry.replace("if (compact) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP", "BUTTON_COMPACT_MIN_WIDTH_DP")),
            "not chosen by the compact flag",
        )
        assertFires(
            "the label growth lost",
            geometryProblems(geometry.replace("maxOf(if (compact) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP, labelWidthDp + 2f * BUTTON_SIDE_DP)", "if (compact) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP")),
            "label growth",
        )
        assertFires(
            "the constant held twice",
            geometryProblems("    const val BUTTON_COMPACT_MIN_WIDTH_DP: Float = 140f / 1.5f\n" + geometry),
            "exactly once",
        )
        assertFires(
            "the constant with another value",
            geometryProblems(geometry.replace("= 140f / 1.5f", "= 100f")),
            "exactly once",
        )
        assertQuiet("the geometry as written", geometryProblems(geometry))
        assertFires(
            "the real floor ignores the flag",
            geometryProblems(onceIn(realText(GEOMETRY), "if (compact) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP", "BUTTON_MIN_WIDTH_DP")),
            "not chosen by the compact flag",
        )
        assertFires(
            "the real constant changed",
            geometryProblems(onceIn(realText(GEOMETRY), "= 140f / 1.5f", "= 100f")),
            "exactly once",
        )
        assertFires(
            "the real label growth lost",
            geometryProblems(onceIn(realText(GEOMETRY), "maxOf(if (compact) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP, labelWidthDp + 2f * BUTTON_SIDE_DP)", "if (compact) BUTTON_COMPACT_MIN_WIDTH_DP else BUTTON_MIN_WIDTH_DP")),
            "label growth",
        )
        assertQuiet("the real geometry", geometryProblems(realText(GEOMETRY)))
    }
}
