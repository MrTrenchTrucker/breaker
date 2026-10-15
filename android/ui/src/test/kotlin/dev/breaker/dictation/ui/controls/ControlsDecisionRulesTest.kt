package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the main, neutral, outlined and destructive buttons, the full-width button and
 * the badge, checked on their source text the way ControlsViewGateTest checks the kit. Each rule
 * runs first on short made-up samples and only then on the real files read from the main tree.
 * A file that cannot be found is a violation, never a pass. Comments and string literals are
 * blanked before a rule reads the code.
 */
class ControlsDecisionRulesTest {

    private val BUTTON = "render/controls/Button3dView.kt"
    private val NEUTRAL = "render/controls/NeutralButtonView.kt"
    private val OUTLINED = "render/controls/OutlinedButtonView.kt"
    private val DELETE = "render/controls/DeleteButtonView.kt"
    private val FULL_WIDTH = "render/controls/FullWidthButtonView.kt"
    private val BADGE = "render/controls/BadgeView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("controls: decision: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("controls: decision: $what must not be reported", emptyList<String>(), problems)

    /** The text of the real file at [path], read from the main tree. */
    private fun realText(path: String): String {
        val text = MAIN_SOURCES.toMap()[path]
        assertTrue("controls: decision: $path was not read from the main tree", text != null)
        return text ?: ""
    }

    /** The code of [text], with its comments and string literals blanked. */
    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    // ---- Buttons: the width goes through the helper; the pressed face is the hook ----

    private val WIDTH_THROUGH_HELPER = Regex("""resolveSize\(\s*buttonWidthPx\(""")
    private val HELPER_DECLARED = Regex("""fun buttonWidthPx\(""")
    private val HELPER_GEOMETRY = Regex("""ControlsGeometry\.buttonWidth\(""")
    private val HELPER_SIZE = Regex("""\bpaint\.textSize = ControlsGeometry\.LABEL_CAP_DP[ \t]*$""", RegexOption.MULTILINE)
    private val HELPER_FACE = Regex("""\bpaint\.typeface = Typeface\.DEFAULT[ \t]*$""", RegexOption.MULTILINE)
    private val HELPER_MEASURE = Regex("""\bpaint\.measureText\(""")
    private val PRESSED_FACE_DRAWN = Regex("""if\s*\(isPressed\)\s*pressedFacePart\(\)""")
    private val PRESSED_FACE_DEFAULT = Regex("""open fun pressedFacePart\(\): ControlPart = ControlPart\.FACE_PRESSED\b""")
    private val DELETE_PRESSED_FACE = Regex("""override fun pressedFacePart\(\): ControlPart = ControlPart\.FACE_DANGER\b""")

    private fun widthProblems(text: String): List<String> =
        if (WIDTH_THROUGH_HELPER.containsMatchIn(codeOf(text))) emptyList()
        else listOf("the width is not measured through buttonWidthPx")

    /** The lines of the helper's body in [code]: from its declaration to the first line that is just a close brace, or empty when either is missing. */
    private fun helperBody(code: String): String {
        val lines = code.lines()
        val start = lines.indexOfFirst { it.contains("fun buttonWidthPx(") }
        val rest = if (start < 0) emptyList<String>() else lines.drop(start + 1)
        val end = rest.indexOfFirst { it == "}" }
        return if (end < 0) "" else rest.take(end).joinToString("\n")
    }

    /** True when [shape] matches in [body] and its first match comes before the first measure. */
    private fun setBeforeMeasure(body: String, shape: Regex): Boolean {
        val measure = HELPER_MEASURE.find(body)?.range?.first ?: return false
        val setAt = shape.find(body)?.range?.first ?: return false
        return setAt < measure
    }

    private fun helperProblems(text: String): List<String> {
        val code = codeOf(text)
        val body = helperBody(code)
        return listOfNotNull(
            "the helper is not declared".takeUnless { HELPER_DECLARED.containsMatchIn(code) },
            "the helper does not ask ControlsGeometry.buttonWidth for the width".takeUnless { HELPER_GEOMETRY.containsMatchIn(code) },
            "the helper does not set the text size before it measures".takeUnless { setBeforeMeasure(body, HELPER_SIZE) },
            "the helper does not set the default face before it measures".takeUnless { setBeforeMeasure(body, HELPER_FACE) },
        )
    }

    private fun pressedFaceProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the pressed face is not drawn through pressedFacePart".takeUnless { PRESSED_FACE_DRAWN.containsMatchIn(code) },
            "pressedFacePart does not default to FACE_PRESSED".takeUnless { PRESSED_FACE_DEFAULT.containsMatchIn(code) },
        )
    }

    private fun deleteFaceProblems(text: String): List<String> =
        if (DELETE_PRESSED_FACE.containsMatchIn(codeOf(text))) emptyList()
        else listOf("the destructive button does not override pressedFacePart with FACE_DANGER")

    /** A failure here means a button's width is the touch floor again, so a short label and a long one get the same width. */
    @Test
    fun `the measured buttons take their width from the label helper`() {
        val measure = "            resolveSize(buttonWidthPx(textPaint, label, density), widthMeasureSpec),\n"
        assertFires(
            "the width at the floor",
            widthProblems(measure.replace("buttonWidthPx(textPaint, label, density)", "touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)")),
            "buttonWidthPx",
        )
        assertQuiet("the width through the helper", widthProblems(measure))
        val helper = "internal fun buttonWidthPx(paint: TextPaint, label: String, density: Float): Int {\n"
        val size = "    paint.textSize = ControlsGeometry.LABEL_CAP_DP\n"
        val face = "    paint.typeface = Typeface.DEFAULT\n"
        val draw = "    val measured = paint.measureText(drawnLabel(label))\n"
        val answer = "    return touchTargetPx(ControlsGeometry.buttonWidth(measured), density)\n}\n"
        assertFires(
            "the helper at the floor",
            helperProblems(helper + "    return touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)\n}\n"),
            "ControlsGeometry.buttonWidth",
        )
        assertFires("the size line missing", helperProblems(helper + face + draw + answer), "text size before it measures")
        assertFires("the size line after the measure", helperProblems(helper + face + draw + size + answer), "text size before it measures")
        assertFires("the face line missing", helperProblems(helper + size + draw + answer), "default face before it measures")
        assertFires("the size line with a factor", helperProblems(helper + "    paint.textSize = ControlsGeometry.LABEL_CAP_DP * 0f\n" + face + draw + answer), "text size before it measures")
        assertQuiet("the helper through the geometry", helperProblems(helper + size + face + draw + answer))
        assertQuiet("the real main button width", widthProblems(realText(BUTTON)))
        assertQuiet("the real main button helper", helperProblems(realText(BUTTON)))
        assertQuiet("the real neutral button width", widthProblems(realText(NEUTRAL)))
        assertQuiet("the real outlined button width", widthProblems(realText(OUTLINED)))
    }

    /** A failure here means the main button paints its face at rest, so the pressed look never shows. */
    @Test
    fun `the main button paints its pressed face through pressedFacePart`() {
        val face = "        paint.color = ControlsPalette.partColor(if (isPressed) pressedFacePart() else facePart(), theme)\n"
        val default = "    protected open fun pressedFacePart(): ControlPart = ControlPart.FACE_PRESSED\n"
        assertFires(
            "the face painted without the hook",
            pressedFaceProblems(face.replace("pressedFacePart() else", "facePart() else")),
            "not drawn through pressedFacePart",
        )
        assertFires(
            "the default moved off the pressed face",
            pressedFaceProblems(default.replace("ControlPart.FACE_PRESSED", "ControlPart.FACE")),
            "default to FACE_PRESSED",
        )
        assertQuiet("the face through the hook and the default", pressedFaceProblems(face + default))
        assertQuiet("the real main button", pressedFaceProblems(realText(BUTTON)))
    }

    /** A failure here means the destructive button presses in the primary colour, not the danger colour. */
    @Test
    fun `the destructive button overrides the pressed face with the danger face`() {
        assertFires(
            "no override at all",
            deleteFaceProblems("class Delete {\n    override fun facePart(): ControlPart = ControlPart.FACE_DANGER\n}\n"),
            "FACE_DANGER",
        )
        assertFires(
            "the press in the primary face",
            deleteFaceProblems("    override fun pressedFacePart(): ControlPart = ControlPart.FACE_PRESSED\n"),
            "FACE_DANGER",
        )
        assertQuiet("the danger override", deleteFaceProblems("    override fun pressedFacePart(): ControlPart = ControlPart.FACE_DANGER\n"))
        assertQuiet("the real destructive button", deleteFaceProblems(realText(DELETE)))
    }

    // ---- The full-width button: the geometry measures it, and it holds no maximum of its own ----

    private val FULL_WIDTH_MEASURE = Regex("""ControlsGeometry\.fullWidthPx\(""")
    private val MAX_OF = Regex("""\bmaxOf\(""")

    private fun fullWidthProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the width is not measured with ControlsGeometry.fullWidthPx".takeUnless { FULL_WIDTH_MEASURE.containsMatchIn(code) },
            "the view holds a maximum of its own".takeIf { MAX_OF.containsMatchIn(code) },
        )
    }

    /** A failure here means the full-width button takes its own maximum, so the geometry no longer decides its width. */
    @Test
    fun `the full-width button measures with the geometry and holds no maximum of its own`() {
        val measure = "            ControlsGeometry.fullWidthPx(row, labelWidthPx(), target),\n"
        assertFires(
            "a maximum of its own",
            fullWidthProblems("        val floor = maxOf(target, labelWidthPx())\n" + measure),
            "maximum of its own",
        )
        assertFires("the measure dropped", fullWidthProblems("            target,\n"), "fullWidthPx")
        assertQuiet("the measure through the geometry", fullWidthProblems(measure))
        assertQuiet("the real full-width button", fullWidthProblems(realText(FULL_WIDTH)))
    }

    // ---- The badge: the face comes from the palette by kind; the pill from the geometry; no touch floor ----

    private val BADGE_FACE = Regex("""ControlsPalette\.badgeFacePart\(kind\)""")
    private val BADGE_KIND_BRANCH = Regex("""BadgeKind\.""")
    private val BADGE_WHEN = Regex("""when\s*\(kind\)""")
    private val BADGE_PILL = Regex("""ControlsGeometry\.badgePill\(""")
    private val BADGE_WIDTH_AXIS = Regex("""resolveSize\(\s*touchTargetPx\(badge\.width,\s*density\),\s*widthMeasureSpec\)""")
    private val BADGE_HEIGHT_AXIS = Regex("""resolveSize\(\s*touchTargetPx\(badge\.height,\s*density\),\s*heightMeasureSpec\)""")

    private fun badgeFaceProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the face is not taken from the palette by kind".takeUnless { BADGE_FACE.containsMatchIn(code) },
            "the view holds a branch on the kind of its own".takeIf { BADGE_KIND_BRANCH.containsMatchIn(code) || BADGE_WHEN.containsMatchIn(code) },
        )
    }

    private fun badgeShapeProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the pill is not centred by ControlsGeometry.badgePill".takeUnless { BADGE_PILL.containsMatchIn(code) },
            "the badge reads the touch floor".takeIf { text.contains("TOUCH_MIN_DP") },
        )
    }

    /** A failure here means the badge picks its own colour for each kind, so the palette no longer decides it. */
    @Test
    fun `the badge takes its face from the palette and holds no kind branch`() {
        assertFires(
            "the face by a local branch",
            badgeFaceProblems("    private fun facePart(): ControlPart = when (kind) {\n        BadgeKind.READY -> ControlPart.FACE\n    }\n"),
            "branch on the kind of its own",
        )
        assertFires(
            "the face without the palette",
            badgeFaceProblems("    private fun facePart(): ControlPart = ControlPart.FACE\n"),
            "from the palette",
        )
        assertQuiet(
            "the face from the palette",
            badgeFaceProblems("    private fun facePart(): ControlPart = ControlsPalette.badgeFacePart(kind)\n"),
        )
        assertQuiet("the real badge face", badgeFaceProblems(realText(BADGE)))
    }

    private fun badgeMeasureProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the badge width is not read from badge.width".takeUnless { BADGE_WIDTH_AXIS.containsMatchIn(code) },
            "the badge height is not read from badge.height".takeUnless { BADGE_HEIGHT_AXIS.containsMatchIn(code) },
        )
    }

    /** A failure here means the badge is sized or centred by the view rather than by the geometry, or the touch floor is back. */
    @Test
    fun `the badge pill is centred by the geometry and the badge has no touch floor`() {
        val pill = "            val pill = ControlsGeometry.badgePill(label, w, h)\n"
        assertFires("the pill drawn by the view", badgeShapeProblems("            val pill = Rect()\n"), "badgePill")
        assertFires(
            "the floor added back",
            badgeShapeProblems(pill + "        val target = touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)\n"),
            "touch floor",
        )
        assertQuiet("the pill from the geometry", badgeShapeProblems(pill))
        assertQuiet("the real badge shape", badgeShapeProblems(realText(BADGE)))
    }

    /** A failure here means the badge is measured with its width and height swapped, so the pill comes out the wrong shape. */
    @Test
    fun `the badge measure reads its own width and height`() {
        val width = "            resolveSize(touchTargetPx(badge.width, density), widthMeasureSpec),\n"
        val height = "            resolveSize(touchTargetPx(badge.height, density), heightMeasureSpec),\n"
        val swapped = "            resolveSize(touchTargetPx(badge.height, density), widthMeasureSpec),\n" +
            "            resolveSize(touchTargetPx(badge.width, density), heightMeasureSpec),\n"
        assertFires("the axes swapped", badgeMeasureProblems(swapped), "badge.width")
        assertFires("the axes swapped, height", badgeMeasureProblems(swapped), "badge.height")
        assertFires(
            "only the width line reads the height",
            badgeMeasureProblems("            resolveSize(touchTargetPx(badge.height, density), widthMeasureSpec),\n" + height),
            "badge.width",
        )
        assertFires(
            "only the height line reads the width",
            badgeMeasureProblems(width + "            resolveSize(touchTargetPx(badge.width, density), heightMeasureSpec),\n"),
            "badge.height",
        )
        assertFires(
            "the width line takes the height size",
            badgeMeasureProblems("            resolveSize(touchTargetPx(badge.width, density), heightMeasureSpec),\n" + height),
            "badge.width",
        )
        assertQuiet("the two lines", badgeMeasureProblems(width + height))
        assertQuiet("the real badge measure", badgeMeasureProblems(realText(BADGE)))
    }
}
