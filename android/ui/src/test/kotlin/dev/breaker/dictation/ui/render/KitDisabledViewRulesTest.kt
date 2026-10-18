package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disabled look of the neutral and outlined buttons, and the press redraw and width those views
 * keep, checked on source text the way the switch and press rules check the kit. Each rule runs
 * first on short made-up samples, so a wrong one is reported with the right words and a correct one
 * is quiet, and only then on the real files read from the main tree. A file that cannot be found is
 * a violation, never a pass. Comments and string literals are blanked before a rule reads the code.
 */
class KitDisabledViewRulesTest {

    private val NEUTRAL = "render/controls/NeutralButtonView.kt"
    private val OUTLINED = "render/controls/OutlinedButtonView.kt"

    /** The line that closes a member declared at the class level. */
    private val MEMBER_CLOSE = "    }"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("render: disabled: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: disabled: $what must not be reported", emptyList<String>(), problems)

    /** The text of the real file at [path], read from the main tree. */
    private fun realFile(path: String): String {
        val text = MAIN_SOURCES.toMap()[path]
        assertTrue("render: disabled: $path was not read from the main tree", text != null)
        return text ?: ""
    }

    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    /**
     * The lines of the member whose header line holds [header]: from the line after the header up to
     * the first line that is only the member's closing brace. Null when no line holds the header.
     */
    private fun memberBody(code: String, header: String): List<String>? {
        val lines = code.lines()
        val start = lines.indexOfFirst { it.contains(header) }
        return if (start < 0) null else lines.drop(start + 1).takeWhile { it != MEMBER_CLOSE }
    }

    /** The member's body as one text, or empty when the member is missing. */
    private fun memberText(code: String, header: String): String =
        memberBody(code, header)?.joinToString("\n") ?: ""

    // ---- Rule (i): the flat look, drawn when the view is disabled ----

    /** The enabled test as a conjunct of the if condition: `isEnabled` alone, or after `&&`, and never negated. */
    private val ENABLED_CONJUNCT = Regex("""\bif\s*\((?:[^()]*?&&\s*)?(?:this\.)?isEnabled\s*(?:&&|\))""")
    /** The else branch that draws the flat look, with or without its own condition. */
    private val FLAT_ELSE = Regex("""\belse\s*(?:if\s*\([^()]*\)\s*)?(?:\{\s*)?drawFlat\(""")
    private val FLAT_GEOMETRY = Regex("""ControlsGeometry\.disabled\(""")
    private val FLAT_LABEL = Regex("""ControlPart\.LABEL_DISABLED\b""")
    private val FLAT_FACE = Regex("""ControlPart\.FACE_SURFACE\b""")
    /** The word boundary keeps OUTLINE_ACCENT from passing as the muted outline. */
    private val FLAT_OUTLINE = Regex("""ControlPart\.OUTLINE\b""")
    private val FLAT_CAPITALS = Regex("""drawnLabel\(label\)""")

    /** Each way the disabled look of a lipped button breaks, one line each. */
    private fun flatProblems(text: String): List<String> {
        val code = codeOf(text)
        val draw = memberText(code, "override fun onDraw(")
        val flat = memberText(code, "private fun drawFlat(")
        return listOfNotNull(
            "onDraw does not branch on isEnabled".takeUnless { ENABLED_CONJUNCT.containsMatchIn(draw) },
            "onDraw does not draw the flat look in its else branch".takeUnless { FLAT_ELSE.containsMatchIn(draw) },
            "the flat look is missing drawFlat".takeIf { flat.isEmpty() },
            "the flat look does not call ControlsGeometry.disabled".takeUnless { FLAT_GEOMETRY.containsMatchIn(flat) },
            "the flat look does not use ControlPart.LABEL_DISABLED".takeUnless { FLAT_LABEL.containsMatchIn(flat) },
            "the flat look does not use ControlPart.FACE_SURFACE".takeUnless { FLAT_FACE.containsMatchIn(flat) },
            "the flat look does not use ControlPart.OUTLINE".takeUnless { FLAT_OUTLINE.containsMatchIn(flat) },
            "the flat look does not draw drawnLabel(label)".takeUnless { FLAT_CAPITALS.containsMatchIn(flat) },
        )
    }

    private val ON_DRAW = "    override fun onDraw(canvas: Canvas) {\n" +
        "        if (w > 0f && h > 0f && isEnabled) {\n" +
        "            drawRaised(canvas)\n" +
        "        } else if (w > 0f && h > 0f) {\n" +
        "            drawFlat(canvas, w, h)\n" +
        "        }\n" +
        "    }\n"

    private val FLAT = "    private fun drawFlat(canvas: Canvas, w: Float, h: Float) {\n" +
        "        val parts = ControlsGeometry.disabled(w, h)\n" +
        "        paint.color = ControlsPalette.partColor(ControlPart.FACE_SURFACE, theme)\n" +
        "        paint.style = Paint.Style.STROKE\n" +
        "        paint.color = ControlsPalette.partColor(ControlPart.OUTLINE, theme)\n" +
        "        textPaint.color = ControlsPalette.partColor(ControlPart.LABEL_DISABLED, theme)\n" +
        "        canvas.drawText(drawnLabel(label), half(w), parts.labelTop - textPaint.fontMetrics.ascent, textPaint)\n" +
        "    }\n"

    /** A failure means a neutral or outlined button shows its enabled look while disabled, or draws the flat look from the wrong parts. */
    @Test
    fun `a disabled neutral or outlined button draws the flat look from the disabled parts`() {
        val sample = ON_DRAW + FLAT
        assertFires(
            "isEnabled left out of the enabled condition",
            flatProblems(sample.replace(" && isEnabled)", ")")),
            "does not branch on isEnabled",
        )
        assertFires(
            "isEnabled negated in the enabled condition",
            flatProblems(sample.replace("&& isEnabled)", "&& !isEnabled)")),
            "does not branch on isEnabled",
        )
        assertFires(
            "the else branch drawing the raised look",
            flatProblems(sample.replace("            drawFlat(canvas, w, h)\n        }\n", "            drawRaised(canvas)\n        }\n")),
            "else branch",
        )
        assertFires(
            "the flat look without the disabled geometry",
            flatProblems(ON_DRAW + FLAT.replace("ControlsGeometry.disabled(w, h)", "ControlsGeometry.neutral(w, h)")),
            "ControlsGeometry.disabled",
        )
        assertFires(
            "the label in the normal colour",
            flatProblems(ON_DRAW + FLAT.replace("LABEL_DISABLED", "LABEL_NEUTRAL")),
            "LABEL_DISABLED",
        )
        assertFires(
            "the face in the page colour",
            flatProblems(ON_DRAW + FLAT.replace("FACE_SURFACE", "FACE_BACKGROUND")),
            "FACE_SURFACE",
        )
        assertFires(
            "the outline in the accent colour",
            flatProblems(ON_DRAW + FLAT.replace("ControlPart.OUTLINE, theme", "ControlPart.OUTLINE_ACCENT, theme")),
            "ControlPart.OUTLINE",
        )
        assertFires(
            "the label written without the capitals helper",
            flatProblems(ON_DRAW + FLAT.replace("drawnLabel(label)", "label")),
            "drawnLabel(label)",
        )
        assertFires("no flat look at all", flatProblems(ON_DRAW), "missing drawFlat")
        assertQuiet("the enabled test and the flat look as written", flatProblems(sample))
        assertQuiet("the real neutral button", flatProblems(realFile(NEUTRAL)))
        assertQuiet("the real outlined button", flatProblems(realFile(OUTLINED)))
    }

    // ---- Rule (ii): the press redraw, super first and invalidate after ----

    private fun pressProblems(text: String): List<String> {
        val body = memberBody(codeOf(text), "override fun drawableStateChanged()")
            ?: return listOf("the view never overrides drawableStateChanged")
        val superAt = body.indexOfFirst { it.contains("super.drawableStateChanged()") }
        val invalidateAt = body.indexOfFirst { it.contains("invalidate()") }
        return listOfNotNull(
            "the body never calls super.drawableStateChanged".takeIf { superAt < 0 },
            "the body never invalidates".takeIf { invalidateAt < 0 },
            "invalidate() runs before super.drawableStateChanged".takeIf { invalidateAt >= 0 && superAt >= 0 && invalidateAt < superAt },
        )
    }

    /** A failure means a press or a release does not redraw a lipped button after the framework has changed its state. */
    @Test
    fun `the neutral and outlined buttons invalidate after the super call on a state change`() {
        val good = "    override fun drawableStateChanged() {\n        super.drawableStateChanged()\n        invalidate()\n    }\n"
        assertFires(
            "invalidate before super",
            pressProblems("    override fun drawableStateChanged() {\n        invalidate()\n        super.drawableStateChanged()\n    }\n"),
            "runs before super",
        )
        assertFires(
            "super call removed",
            pressProblems("    override fun drawableStateChanged() {\n        invalidate()\n    }\n"),
            "never calls super",
        )
        assertFires(
            "invalidate removed",
            pressProblems("    override fun drawableStateChanged() {\n        super.drawableStateChanged()\n    }\n"),
            "never invalidates",
        )
        assertFires(
            "no override",
            pressProblems("    override fun onDraw(canvas: Canvas) {\n    }\n"),
            "never overrides",
        )
        assertQuiet("super first and invalidate after", pressProblems(good))
        assertQuiet("the real neutral button", pressProblems(realFile(NEUTRAL)))
        assertQuiet("the real outlined button", pressProblems(realFile(OUTLINED)))
    }

    // ---- Rule (iii): the width still goes through the label helper ----

    private val WIDTH_THROUGH_HELPER = Regex("""resolveSize\(\s*buttonWidthPx\(""")

    private fun widthProblems(text: String): List<String> =
        if (WIDTH_THROUGH_HELPER.containsMatchIn(memberText(codeOf(text), "override fun onMeasure("))) emptyList()
        else listOf("the width is not measured through buttonWidthPx")

    /** A failure means a neutral or outlined button takes its width from the touch floor, so a long label gets the width of a short one. */
    @Test
    fun `the neutral and outlined buttons measure their width through the label helper`() {
        val measure = "    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {\n" +
            "        setMeasuredDimension(\n" +
            "            resolveSize(buttonWidthPx(textPaint, label, density), widthMeasureSpec),\n" +
            "            resolveSize(target, heightMeasureSpec),\n" +
            "        )\n" +
            "    }\n"
        assertFires(
            "the width at the floor",
            widthProblems(measure.replace("buttonWidthPx(textPaint, label, density)", "touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)")),
            "buttonWidthPx",
        )
        assertFires(
            "the helper called without resolveSize",
            widthProblems(measure.replace("resolveSize(buttonWidthPx(textPaint, label, density), widthMeasureSpec)", "buttonWidthPx(textPaint, label, density)")),
            "buttonWidthPx",
        )
        assertFires("no onMeasure", widthProblems("    override fun onDraw(canvas: Canvas) {\n    }\n"), "buttonWidthPx")
        assertQuiet("the helper through resolveSize", widthProblems(measure))
        assertQuiet("the real neutral button", widthProblems(realFile(NEUTRAL)))
        assertQuiet("the real outlined button", widthProblems(realFile(OUTLINED)))
    }
}
