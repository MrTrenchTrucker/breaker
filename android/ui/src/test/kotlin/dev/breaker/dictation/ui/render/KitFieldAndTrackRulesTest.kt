package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch row's track, the value field's muted flag and the kit text field's words, checked on
 * source text the way the switch and press rules check the kit. Each rule runs first on short
 * made-up samples, so a wrong one is reported with the right words and a correct one is quiet, and
 * only then on the real files read from the main tree. A file that cannot be found is a violation,
 * never a pass. Comments and string literals are blanked before a rule reads the code.
 */
class KitFieldAndTrackRulesTest {

    private val SWITCH_ROW = "render/SwitchRowView.kt"
    private val VALUE_FIELD = "render/ValueFieldView.kt"
    private val TEXT_FIELD = "render/controls/TextFieldView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("render: field and track: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: field and track: $what must not be reported", emptyList<String>(), problems)

    /** The text of the real file at [path], read from the main tree. */
    private fun realFile(path: String): String {
        val text = MAIN_SOURCES.toMap()[path]
        assertTrue("render: field and track: $path was not read from the main tree", text != null)
        return text ?: ""
    }

    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    // ---- Rule (iv): the switch row draws its track from the track pair, in order ----

    private val TRACK_W = Regex("""ControlsGeometry\.TRACK_W_DP\b""")
    private val TRACK_H = Regex("""ControlsGeometry\.TRACK_H_DP\b""")
    private val BORROWED = Regex("""\b(?:TOUCH_MIN_DP|BADGE_H_DP)\b""")
    private val SWAPPED = Regex(
        """ControlsGeometry\.TRACK_H_DP\s*,\s*ControlsGeometry\.TRACK_W_DP|trackW\s*=\s*ControlsGeometry\.TRACK_H_DP|trackH\s*=\s*ControlsGeometry\.TRACK_W_DP""",
    )

    private fun trackProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the switch row does not name ControlsGeometry.TRACK_W_DP".takeUnless { TRACK_W.containsMatchIn(code) },
            "the switch row does not name ControlsGeometry.TRACK_H_DP".takeUnless { TRACK_H.containsMatchIn(code) },
            "the switch row still borrows a kit constant".takeIf { BORROWED.containsMatchIn(code) },
            "the track pair is swapped".takeIf { SWAPPED.containsMatchIn(code) },
        )
    }

    /** A failure means the switch row takes its track from a kit constant that is not the track, or passes the pair in the wrong order. */
    @Test
    fun `the switch row draws its track from the track pair in order`() {
        val pair = "        SwitchView(\n" +
            "            context,\n" +
            "            row.label,\n" +
            "            ControlsGeometry.TRACK_W_DP,\n" +
            "            ControlsGeometry.TRACK_H_DP,\n" +
            "            theme,\n" +
            "        )\n"
        assertFires(
            "the touch floor left as the width",
            trackProblems(pair.replace("ControlsGeometry.TRACK_W_DP", "ControlsFactors.TOUCH_MIN_DP")),
            "still borrows",
        )
        assertFires(
            "the badge height left as the height",
            trackProblems(pair.replace("ControlsGeometry.TRACK_H_DP", "ControlsGeometry.BADGE_H_DP")),
            "still borrows",
        )
        assertFires(
            "the height argument missing",
            trackProblems(pair.replace("            ControlsGeometry.TRACK_H_DP,\n", "")),
            "TRACK_H_DP",
        )
        assertFires(
            "the pair swapped by position",
            trackProblems(pair.replace("TRACK_W_DP", "TRACK_X").replace("TRACK_H_DP", "TRACK_W_DP").replace("TRACK_X", "TRACK_H_DP")),
            "swapped",
        )
        assertFires(
            "the pair swapped by name",
            trackProblems(
                "        SwitchView(\n" +
                    "            context,\n" +
                    "            row.label,\n" +
                    "            trackW = ControlsGeometry.TRACK_H_DP,\n" +
                    "            trackH = ControlsGeometry.TRACK_W_DP,\n" +
                    "            theme,\n" +
                    "        )\n",
            ),
            "swapped",
        )
        assertQuiet("the pair in order", trackProblems(pair))
        assertQuiet(
            "the pair named in another order",
            trackProblems(
                "        SwitchView(\n" +
                    "            context,\n" +
                    "            row.label,\n" +
                    "            trackH = ControlsGeometry.TRACK_H_DP,\n" +
                    "            trackW = ControlsGeometry.TRACK_W_DP,\n" +
                    "            theme,\n" +
                    "        )\n",
            ),
        )
        assertQuiet("the real switch row", trackProblems(realFile(SWITCH_ROW)))
    }

    // ---- Rule (v): the value field passes its muted flag as the last argument ----

    private val TEXT_FIELD_CALL = Regex("""TextFieldView\(([^)]*)\)""")
    private val NAMED_MUTED = Regex("""^muted\s*=\s*""")

    private fun mutedArgProblems(text: String): List<String> {
        val args = TEXT_FIELD_CALL.find(codeOf(text))?.groupValues?.get(1)
            ?: return listOf("the value field does not call TextFieldView")
        val last = args.split(",").map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull().orEmpty()
        return if (last.replace(NAMED_MUTED, "") == "node.muted") emptyList()
        else listOf("the value field does not pass node.muted as the last argument of TextFieldView")
    }

    /** A failure means the value field does not hand its muted flag to the kit field, so a value not set reads as typed. */
    @Test
    fun `the value field passes its muted flag into the kit field`() {
        val good = "internal fun valueFieldView(context: Context, node: ValueField, theme: Theme): View =\n" +
            "    TextFieldView(context, node.text, theme, node.muted).apply {\n"
        assertFires("a false in place of the flag", mutedArgProblems(good.replace("node.muted)", "false)")), "node.muted")
        assertFires("the flag negated", mutedArgProblems(good.replace("node.muted)", "!node.muted)")), "node.muted")
        assertFires("the flag left out", mutedArgProblems(good.replace(", node.muted)", ")")), "node.muted")
        assertFires("no call at all", mutedArgProblems("val unused = 0\n"), "does not call TextFieldView")
        assertQuiet("the flag as the last argument", mutedArgProblems(good))
        assertQuiet("the flag named as the last argument", mutedArgProblems(good.replace("node.muted)", "muted = node.muted)")))
        assertQuiet("the real value field", mutedArgProblems(realFile(VALUE_FIELD)))
    }

    // ---- Rule (vi): the words take the muted part when muted, and keep the monospace face ----

    private val MUTED_WORDS = Regex("""\bif\s*\(\s*muted\s*\)\s*ControlPart\.FIELD_TEXT_MUTED\s+else\s+ControlPart\.LABEL_NEUTRAL\b""")
    private val UNMUTED_WORDS = Regex("""\bif\s*\(\s*!\s*muted\s*\)\s*ControlPart\.LABEL_NEUTRAL\s+else\s+ControlPart\.FIELD_TEXT_MUTED\b""")
    private val WORDS_COLOUR = Regex("""textPaint\.color\s*=\s*ControlsPalette\.partColor\(\s*wordsPart\s*,""")
    private val MONO_FACE = Regex("""\.typeface\s*=\s*Typeface\.MONOSPACE\b""")

    private fun wordsProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the words do not take FIELD_TEXT_MUTED when muted and LABEL_NEUTRAL otherwise"
                .takeUnless { MUTED_WORDS.containsMatchIn(code) || UNMUTED_WORDS.containsMatchIn(code) },
            "the words colour is not set through ControlsPalette.partColor(wordsPart".takeUnless { WORDS_COLOUR.containsMatchIn(code) },
            "the field does not keep Typeface.MONOSPACE".takeUnless { MONO_FACE.containsMatchIn(code) },
        )
    }

    /** A failure means the words of a muted field are not drawn in the muted colour, or the field loses its monospace face. */
    @Test
    fun `the field words take the muted part when muted and keep the monospace face`() {
        val good = "            val wordsPart = if (muted) ControlPart.FIELD_TEXT_MUTED else ControlPart.LABEL_NEUTRAL\n" +
            "            textPaint.color = ControlsPalette.partColor(wordsPart, theme)\n" +
            "            textPaint.typeface = Typeface.MONOSPACE\n"
        val inverted = good.replace(
            "if (muted) ControlPart.FIELD_TEXT_MUTED else ControlPart.LABEL_NEUTRAL",
            "if (muted) ControlPart.LABEL_NEUTRAL else ControlPart.FIELD_TEXT_MUTED",
        )
        assertFires("the branch inverted", wordsProblems(inverted), "do not take FIELD_TEXT_MUTED")
        assertFires(
            "the flag ignored",
            wordsProblems(good.replace("if (muted) ControlPart.FIELD_TEXT_MUTED else ControlPart.LABEL_NEUTRAL", "ControlPart.LABEL_NEUTRAL")),
            "do not take FIELD_TEXT_MUTED",
        )
        assertFires("the default face", wordsProblems(good.replace("Typeface.MONOSPACE", "Typeface.DEFAULT")), "keep Typeface.MONOSPACE")
        assertFires(
            "the colour set by hand",
            wordsProblems(good.replace("ControlsPalette.partColor(wordsPart, theme)", "theme.color(PaletteSlot.TEXT_MUTED).argb")),
            "partColor",
        )
        assertFires(
            "the colour set to the normal part, with the choice left unused",
            wordsProblems(good.replace("ControlsPalette.partColor(wordsPart, theme)", "ControlsPalette.partColor(ControlPart.LABEL_NEUTRAL, theme)")),
            "partColor(wordsPart",
        )
        assertQuiet("the branch as written", wordsProblems(good))
        assertQuiet(
            "the branch with the flag negated and the parts swapped",
            wordsProblems(
                good.replace("if (muted)", "if (!muted)")
                    .replace("ControlPart.FIELD_TEXT_MUTED else ControlPart.LABEL_NEUTRAL", "ControlPart.LABEL_NEUTRAL else ControlPart.FIELD_TEXT_MUTED"),
            ),
        )
        assertQuiet("the real text field", wordsProblems(realFile(TEXT_FIELD)))
    }
}
