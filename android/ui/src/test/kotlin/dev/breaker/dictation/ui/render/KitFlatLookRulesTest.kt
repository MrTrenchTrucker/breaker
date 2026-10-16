package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flat look of the neutral and outlined buttons, checked one line at a time over the body of
 * their drawFlat member only, so the enabled path of the same view cannot hide a missing step. The
 * steps must come in one order, the body must draw exactly two rounded faces, it must make
 * exactly three canvas calls in all, and no line of the body may fall outside the fourteen steps. Each rule runs first on short made-up samples, so a wrong one
 * is reported with the right step and a correct one is quiet, and only then on the real files read
 * from the main tree. A file that cannot be found is a violation, never a pass. Comments and string
 * literals are blanked before a rule reads the code.
 */
class KitFlatLookRulesTest {

    private val NEUTRAL = "render/controls/NeutralButtonView.kt"
    private val OUTLINED = "render/controls/OutlinedButtonView.kt"

    /** The line that closes a member declared at the class level. */
    private val MEMBER_CLOSE = "    }"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("render: flat look: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: flat look: $what must not be reported", emptyList<String>(), problems)

    /** The text of the real file at [path], read from the main tree. */
    private fun realFile(path: String): String {
        val text = MAIN_SOURCES.toMap()[path]
        assertTrue("render: flat look: $path was not read from the main tree", text != null)
        return text ?: ""
    }

    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    /** The body of the drawFlat member of [text], one text, or empty when the member is missing. */
    private fun flatBody(text: String): String {
        val lines = codeOf(text).lines()
        val start = lines.indexOfFirst { it.contains("private fun drawFlat(") }
        return if (start < 0) "" else lines.drop(start + 1).takeWhile { it != MEMBER_CLOSE }.joinToString("\n")
    }

    private val ONE = 1
    private val FACES_WANTED = ONE + ONE
    private val THREE = ONE + ONE + ONE
    private val CANVAS_CALLS_WANTED = THREE

    private val ROUNDED = Regex("""canvas\.drawRoundRect\(""")
    /** The word, so an alias such as `val c = canvas` counts too. */
    private val CANVAS = Regex("""\bcanvas\b""")
    private val PARTS = Regex("""val parts = ControlsGeometry\.disabled\(w, h\)""")
    private val CORNER = Regex("""val corner = ControlsFactors\.CORNER_DP\b""")
    private val FILL = Regex("""paint\.style\s*=\s*Paint\.Style\.FILL\b""")
    private val FACE = Regex("""paint\.color\s*=\s*ControlsPalette\.partColor\(\s*ControlPart\.FACE_SURFACE\s*,\s*theme\s*\)""")
    private val FIRST_ROUNDED = Regex("""canvas\.drawRoundRect\(\s*0f\s*,\s*0f\s*,\s*w\s*,\s*h\s*,\s*corner\s*,\s*corner\s*,\s*paint\s*\)""")
    private val STROKE = Regex("""paint\.style\s*=\s*Paint\.Style\.STROKE\b""")
    private val WIDTH = Regex("""paint\.strokeWidth\s*=\s*ControlsGeometry\.OUTLINE_DP\b""")
    private val OUTLINE = Regex("""paint\.color\s*=\s*ControlsPalette\.partColor\(\s*ControlPart\.OUTLINE\s*,\s*theme\s*\)""")
    private val LABEL_COLOUR = Regex("""textPaint\.color\s*=\s*ControlsPalette\.partColor\(\s*ControlPart\.LABEL_DISABLED\s*,\s*theme\s*\)""")
    private val LABEL_SIZE = Regex("""textPaint\.textSize\s*=\s*ControlsGeometry\.LABEL_CAP_DP\b""")
    private val LABEL_FACE = Regex("""textPaint\.typeface\s*=\s*Typeface\.DEFAULT\b""")
    private val LABEL_ALIGN = Regex("""textPaint\.textAlign\s*=\s*Paint\.Align\.CENTER\b""")
    private val LABEL_DRAW = Regex(
        """canvas\.drawText\(\s*drawnLabel\(label\)\s*,\s*half\(w\)\s*,\s*parts\.labelTop\s*-\s*textPaint\.fontMetrics\.ascent\s*,\s*textPaint\s*\)""",
    )

    /** The steps in the order the body must draw them, each with the name a problem reports it by. */
    private val STEPS = listOf(
        "disabled parts" to PARTS,
        "CORNER_DP corner" to CORNER,
        "first FILL" to FILL,
        "FACE_SURFACE fill" to FACE,
        "first rounded face" to FIRST_ROUNDED,
        "STROKE" to STROKE,
        "OUTLINE_DP width" to WIDTH,
        "OUTLINE colour" to OUTLINE,
        "second rounded face" to FIRST_ROUNDED,
        "LABEL_DISABLED colour" to LABEL_COLOUR,
        "LABEL_CAP_DP size" to LABEL_SIZE,
        "DEFAULT face" to LABEL_FACE,
        "CENTER align" to LABEL_ALIGN,
        "drawnLabel text" to LABEL_DRAW,
    )

    /**
     * Each step found after the one before it, exactly two rounded faces, exactly three canvas calls, and no line
     * that is not one of the steps (the allowlist: every body line must match a step's whole statement, and a step line
     * may appear only as often as the steps name it); one problem per miss.
     */
    private fun orderedProblems(body: String): List<String> {
        if (body.isEmpty()) return listOf("the flat look is missing drawFlat")
        val problems = mutableListOf<String>()
        var cursor = 0
        for ((name, shape) in STEPS) {
            val found = shape.find(body, cursor)
            if (found == null) problems.add("the flat look misses step $name") else cursor = found.range.last + 1
        }
        val faces = ROUNDED.findAll(body).count()
        if (faces != FACES_WANTED) problems.add("the flat look does not draw exactly two rounded faces, found $faces")
        val calls = CANVAS.findAll(body).count()
        if (calls != CANVAS_CALLS_WANTED) problems.add("the flat look does not make exactly three canvas calls, found $calls")
        val code = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in code) {
            if (STEPS.none { it.second.matches(line) }) problems.add("unexpected line in the flat look: $line")
        }
        for (shape in STEPS.map { it.second }.distinct()) {
            val wanted = STEPS.count { it.second == shape }
            code.filter { shape.matches(it) }.drop(wanted).forEach { problems.add("unexpected line in the flat look: $it") }
        }
        return problems
    }

    private val L_PARTS = "        val parts = ControlsGeometry.disabled(w, h)\n"
    private val L_CORNER = "        val corner = ControlsFactors.CORNER_DP\n"
    private val L_FILL = "        paint.style = Paint.Style.FILL\n"
    private val L_FACE = "        paint.color = ControlsPalette.partColor(ControlPart.FACE_SURFACE, theme)\n"
    private val L_RR = "        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)\n"
    private val L_STROKE = "        paint.style = Paint.Style.STROKE\n"
    private val L_WIDTH = "        paint.strokeWidth = ControlsGeometry.OUTLINE_DP\n"
    private val L_OUTLINE = "        paint.color = ControlsPalette.partColor(ControlPart.OUTLINE, theme)\n"
    private val L_LABEL_COLOUR = "        textPaint.color = ControlsPalette.partColor(ControlPart.LABEL_DISABLED, theme)\n"
    private val L_LABEL_SIZE = "        textPaint.textSize = ControlsGeometry.LABEL_CAP_DP\n"
    private val L_LABEL_FACE = "        textPaint.typeface = Typeface.DEFAULT\n"
    private val L_ALIGN = "        textPaint.textAlign = Paint.Align.CENTER\n"
    private val L_DRAW = "        canvas.drawText(drawnLabel(label), half(w), parts.labelTop - textPaint.fontMetrics.ascent, textPaint)\n"
    private val L_RECT = "        canvas.drawRect(0f, 0f, w, h, paint)\n"
    private val L_LINE = "        canvas.drawLine(0f, 0f, w, h, paint)\n"

    /** The lines of the correct flat look, in order: the two head lines, then the paint steps. */
    private val LINES = listOf(
        L_PARTS, L_CORNER,
        L_FILL, L_FACE, L_RR, L_STROKE, L_WIDTH, L_OUTLINE, L_RR,
        L_LABEL_COLOUR, L_LABEL_SIZE, L_LABEL_FACE, L_ALIGN, L_DRAW,
    )

    /** Where the second rounded face sits in [LINES], the last line that draws one. */
    private val SECOND_FACE_AT = LINES.lastIndexOf(L_RR)

    /** The names a deleted line is reported by, in the order of [LINES]; deleting a rounded face is reported by the count or the second face. */
    private val DELETED_NAME = listOf(
        "disabled parts", "CORNER_DP corner", "first FILL", "FACE_SURFACE fill", "exactly two rounded faces", "STROKE",
        "OUTLINE_DP width", "OUTLINE colour", "second rounded face", "LABEL_DISABLED colour", "LABEL_CAP_DP size",
        "DEFAULT face", "CENTER align", "drawnLabel text",
    )

    /** A drawFlat member holding [lines] in order, closed the way the real members close. */
    private fun sampleOf(lines: List<String>): String =
        "    private fun drawFlat(canvas: Canvas, w: Float, h: Float) {\n" +
            lines.joinToString("") + "    }\n"

    /** A failure means one line of the flat look is missing, out of order, or the face or canvas-call count is wrong. */
    @Test
    fun `each line of the flat look, deleted on its own, is reported by its step`() {
        for ((index, name) in DELETED_NAME.withIndex()) {
            assertFires(
                "line $index deleted",
                orderedProblems(flatBody(sampleOf(LINES.filterIndexed { i, _ -> i != index }))),
                name,
            )
        }
    }

    /** A failure means a style, an alignment, a size, a colour or a constant of the flat look is swapped for another one. */
    @Test
    fun `a changed style, alignment, size, colour or constant in the flat look is reported`() {
        val good = sampleOf(LINES)
        assertFires(
            "the corner constant changed to a number",
            orderedProblems(flatBody(good.replace("ControlsFactors.CORNER_DP", "0f"))),
            "CORNER_DP corner",
        )
        assertFires(
            "the disabled parts taken from the neutral geometry",
            orderedProblems(flatBody(good.replace("ControlsGeometry.disabled(w, h)", "ControlsGeometry.neutral(w, h)"))),
            "disabled parts",
        )
        assertFires(
            "the stroke swapped to fill",
            orderedProblems(flatBody(good.replace("Paint.Style.STROKE", "Paint.Style.FILL"))),
            "STROKE",
        )
        assertFires(
            "the centre alignment swapped to left",
            orderedProblems(flatBody(good.replace("Paint.Align.CENTER", "Paint.Align.LEFT"))),
            "CENTER align",
        )
        assertFires(
            "the label size swapped to the outline width",
            orderedProblems(flatBody(good.replace("textPaint.textSize = ControlsGeometry.LABEL_CAP_DP", "textPaint.textSize = ControlsGeometry.OUTLINE_DP"))),
            "LABEL_CAP_DP size",
        )
        assertFires(
            "the outline width swapped to the label size",
            orderedProblems(flatBody(good.replace("paint.strokeWidth = ControlsGeometry.OUTLINE_DP", "paint.strokeWidth = ControlsGeometry.LABEL_CAP_DP"))),
            "OUTLINE_DP width",
        )
        assertFires(
            "the outline in the accent colour",
            orderedProblems(flatBody(good.replace("ControlPart.OUTLINE, theme", "ControlPart.OUTLINE_ACCENT, theme"))),
            "OUTLINE colour",
        )
        assertFires(
            "the label in the normal colour",
            orderedProblems(flatBody(good.replace("ControlPart.LABEL_DISABLED, theme", "ControlPart.LABEL_NEUTRAL, theme"))),
            "LABEL_DISABLED colour",
        )
        assertFires(
            "the label written without the capitals helper",
            orderedProblems(flatBody(good.replace("drawnLabel(label)", "label"))),
            "drawnLabel text",
        )
    }

    /** A failure means the outline is drawn before the second face, a face or another canvas call is added, or a count is wrong. */
    @Test
    fun `the stroke steps come before the second face and the body makes exactly three canvas calls`() {
        val moved = listOf(L_PARTS, L_CORNER, L_FILL, L_FACE, L_RR, L_RR, L_STROKE, L_WIDTH, L_OUTLINE) + LINES.drop(SECOND_FACE_AT + 1)
        assertFires("the stroke steps moved after the second face", orderedProblems(flatBody(sampleOf(moved))), "second rounded face")
        val third = LINES.take(SECOND_FACE_AT + 1) + L_RR + LINES.drop(SECOND_FACE_AT + 1)
        assertFires("a third face added", orderedProblems(flatBody(sampleOf(third))), "exactly two rounded faces")
        assertFires("no face at all", orderedProblems(flatBody(sampleOf(LINES.filterNot { it == L_RR }))), "exactly two rounded faces")
        assertFires(
            "a rectangle added at the end",
            orderedProblems(flatBody(sampleOf(LINES + L_RECT))),
            "exactly three canvas calls",
        )
        assertFires(
            "a rectangle added before the second face",
            orderedProblems(flatBody(sampleOf(LINES.take(SECOND_FACE_AT) + L_RECT + LINES.drop(SECOND_FACE_AT)))),
            "exactly three canvas calls",
        )
        assertFires(
            "a line added at the end",
            orderedProblems(flatBody(sampleOf(LINES + L_LINE))),
            "exactly three canvas calls",
        )
        assertFires(
            "a second label drawn at the end",
            orderedProblems(flatBody(sampleOf(LINES + L_DRAW))),
            "exactly three canvas calls",
        )
    }

    /** A failure means the correct flat look is reported, or a real button's flat look misses a step. */
    @Test
    fun `the correct flat look is quiet and both real buttons draw every step in order`() {
        assertQuiet("the correct flat look", orderedProblems(flatBody(sampleOf(LINES))))
        assertQuiet("the real neutral button", orderedProblems(flatBody(realFile(NEUTRAL))))
        assertQuiet("the real outlined button", orderedProblems(flatBody(realFile(OUTLINED))))
    }

    /** A failure means a statement that is not one of the fourteen steps changes the flat look where no step notices it. */
    @Test
    fun `a statement that is not one of the fourteen steps is reported as an unexpected line`() {
        val unexpected = "unexpected line in the flat look: "
        val alpha = "        paint.alpha = 0\n"
        val afterFill = LINES.indexOf(L_FILL) + ONE
        val afterFace = LINES.indexOf(L_FACE) + ONE
        val afterAlign = LINES.indexOf(L_ALIGN) + ONE
        val afterLabelColour = LINES.indexOf(L_LABEL_COLOUR) + ONE
        assertFires("a second label size after the label colour", orderedProblems(flatBody(sampleOf(LINES.take(afterLabelColour) + L_LABEL_SIZE + LINES.drop(afterLabelColour)))), unexpected + "textPaint.textSize = ControlsGeometry.LABEL_CAP_DP")
        assertFires("a paint alpha inserted after the fill", orderedProblems(flatBody(sampleOf(LINES.take(afterFill) + alpha + LINES.drop(afterFill)))), unexpected + "paint.alpha = 0")
        assertFires(
            "an anti-alias setter at the end",
            orderedProblems(flatBody(sampleOf(LINES + "        paint.isAntiAlias = false\n"))),
            unexpected + "paint.isAntiAlias = false",
        )
        assertFires(
            "a letter spacing after the alignment",
            orderedProblems(flatBody(sampleOf(LINES.take(afterAlign) + "        textPaint.letterSpacing = 0f\n" + LINES.drop(afterAlign)))),
            unexpected + "textPaint.letterSpacing = 0f",
        )
        assertFires(
            "a face colour in another part, between the fill colour and the first face",
            orderedProblems(flatBody(sampleOf(LINES.take(afterFace) + "        paint.color = ControlsPalette.partColor(ControlPart.LIP_NEUTRAL, theme)\n" + LINES.drop(afterFace)))),
            unexpected + "paint.color = ControlsPalette.partColor(ControlPart.LIP_NEUTRAL, theme)",
        )
        val alias = sampleOf(LINES + "        val c = canvas\n" + "        c.drawRect(0f, 0f, w, h, paint)\n")
        assertFires("an alias of the canvas", orderedProblems(flatBody(alias)), unexpected + "val c = canvas")
        assertFires("a drawing call through the alias", orderedProblems(flatBody(alias)), unexpected + "c.drawRect(0f, 0f, w, h, paint)")
        assertFires(
            "an if wrapper around the body",
            orderedProblems(flatBody(sampleOf(listOf("        if (isEnabled) {\n") + LINES))),
            unexpected + "if (isEnabled) {",
        )
        assertFires("an empty statement at the end", orderedProblems(flatBody(sampleOf(LINES + "        w\n"))), unexpected + "w")
        for (path in listOf(NEUTRAL, OUTLINED)) {
            val real = realFile(path)
            val mutated = real.replace(L_FILL, L_FILL + alpha)
            assertTrue("render: flat look: the inserted alpha line did not land in $path", mutated != real)
            assertFires("a paint alpha inserted in the real $path", orderedProblems(flatBody(mutated)), unexpected + "paint.alpha = 0")
        }
    }
}
