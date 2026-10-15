package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step marks and the text field's face, checked on their source text the way
 * ControlsViewGateTest checks the kit. The marks are lit by the current step through the
 * geometry, and the field alone is set in the monospace face while every other view that sets a
 * face keeps the default one. Each rule runs first on short made-up samples and only then on the
 * real files read from the main tree. A file that cannot be found is a violation, never a pass.
 * Comments and string literals are blanked before a rule reads the code.
 */
class ControlsStepAndFaceRulesTest {

    private val BUTTON = "render/controls/Button3dView.kt"
    private val NEUTRAL = "render/controls/NeutralButtonView.kt"
    private val OUTLINED = "render/controls/OutlinedButtonView.kt"
    private val DELETE = "render/controls/DeleteButtonView.kt"
    private val FULL_WIDTH = "render/controls/FullWidthButtonView.kt"
    private val BADGE = "render/controls/BadgeView.kt"
    private val SWITCH = "render/controls/SwitchView.kt"
    private val STEPS = "render/controls/StepMarksView.kt"
    private val FIELD = "render/controls/TextFieldView.kt"

    /** Every view file that is not the field, for the rule that each keeps the default face. */
    private val OTHER_VIEWS = listOf(BUTTON, NEUTRAL, OUTLINED, DELETE, FULL_WIDTH, BADGE, SWITCH, STEPS)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("controls: steps and face: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("controls: steps and face: $what must not be reported", emptyList<String>(), problems)

    /** The text of the real file at [path], read from the main tree. */
    private fun realText(path: String): String {
        val text = MAIN_SOURCES.toMap()[path]
        assertTrue("controls: steps and face: $path was not read from the main tree", text != null)
        return text ?: ""
    }

    /** The code of [text], with its comments and string literals blanked. */
    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    // ---- The step marks: lit by the current step through isStepLit ----

    private val STEP_CURRENT = Regex("""val current: Int\b""")
    private val STEP_LIT = Regex("""ControlsGeometry\.isStepLit\(index, current\)""")
    private val STEP_INDEX_ZERO = Regex("""index\s*==\s*0\b""")

    private fun stepProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the view takes no current step".takeUnless { STEP_CURRENT.containsMatchIn(code) },
            "the path is not chosen by ControlsGeometry.isStepLit".takeUnless { STEP_LIT.containsMatchIn(code) },
            "the path is still chosen by index == 0".takeIf { STEP_INDEX_ZERO.containsMatchIn(code) },
        )
    }

    /** A failure here means the marks light by a fixed index, so the step the flow is on never reaches them. */
    @Test
    fun `the step marks light from the current step through isStepLit`() {
        val path = "    private val current: Int,\n" +
            "                val path = if (ControlsGeometry.isStepLit(index, current)) done else todo\n"
        assertFires(
            "the old index test",
            stepProblems(path.replace("ControlsGeometry.isStepLit(index, current)", "index == 0")),
            "index == 0",
        )
        assertFires(
            "the current step removed",
            stepProblems(path.replace("private val current: Int,", "private val step: Int,")),
            "no current step",
        )
        assertFires(
            "the path chosen by hand",
            stepProblems(path.replace("ControlsGeometry.isStepLit(index, current)", "index < current")),
            "isStepLit",
        )
        assertQuiet("the path through isStepLit", stepProblems(path))
        assertQuiet("the real step marks", stepProblems(realText(STEPS)))
    }

    // ---- The text field: monospace; every other view that sets a face keeps the default ----

    private val MONO = Regex("""Typeface\.MONOSPACE\b""")
    private val DEFAULT_FACE = Regex("""Typeface\.DEFAULT\b""")
    private val SETS_FACE = Regex("""\.typeface\s*=""")

    private fun fieldFaceProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the field does not use the monospace face".takeUnless { MONO.containsMatchIn(code) },
            "the field uses the default face".takeIf { DEFAULT_FACE.containsMatchIn(code) },
        )
    }

    private fun otherFaceProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the view uses the monospace face".takeIf { MONO.containsMatchIn(code) },
            "the view sets a face without the default face".takeIf { SETS_FACE.containsMatchIn(code) && !DEFAULT_FACE.containsMatchIn(code) },
        )
    }

    /** A failure here means the field's words are not in the monospace face, or another view takes the monospace face or moves off the default face. */
    @Test
    fun `the field is set in the monospace face and every other view keeps the default face`() {
        assertFires(
            "the field on the default face",
            fieldFaceProblems("            textPaint.typeface = Typeface.DEFAULT\n"),
            "default face",
        )
        assertFires(
            "the field with no face",
            fieldFaceProblems("            textPaint.color = ControlsPalette.partColor(ControlPart.FIELD, theme)\n"),
            "monospace",
        )
        assertFires(
            "a sibling on the monospace face",
            otherFaceProblems("            textPaint.typeface = Typeface.MONOSPACE\n"),
            "uses the monospace face",
        )
        assertFires(
            "a sibling with both faces",
            otherFaceProblems("            textPaint.typeface = Typeface.DEFAULT\n            textPaint.typeface = Typeface.MONOSPACE\n"),
            "uses the monospace face",
        )
        assertFires(
            "a sibling on a face of its own",
            otherFaceProblems("            textPaint.typeface = Typeface.SANS_SERIF\n"),
            "without the default face",
        )
        assertQuiet("the field on the monospace face", fieldFaceProblems("            textPaint.typeface = Typeface.MONOSPACE\n"))
        assertQuiet("a sibling on the default face", otherFaceProblems("            textPaint.typeface = Typeface.DEFAULT\n"))
        assertQuiet("a view with no face", otherFaceProblems("            paint.color = ControlsPalette.partColor(ControlPart.FACE, theme)\n"))
        assertQuiet("the real field", fieldFaceProblems(realText(FIELD)))
        OTHER_VIEWS.forEach { path -> assertQuiet("the real $path", otherFaceProblems(realText(path))) }
    }
}
