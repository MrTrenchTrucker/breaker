package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the tile view paints with which colour and where: which colour of the look the ring and the notice text use,
 * which rectangle the ring is drawn into, and the guard of the view and the width of the ring. The buttons are filled
 * shapes drawn by drawControl, and their colours are checked by the face rules and the outline rules. The art, its
 * faces and its halo passes are checked by their own rules. The view cannot run on a plain JVM, so these rules read
 * its source text.
 *
 * Every test runs its rule first on short made-up samples (a wrong line must be reported with the right words, a
 * correct one must not, even with other names) and only then on the real file. A construct that cannot be found
 * is a violation, never a pass. Comments are blanked, strings are kept.
 */
class TileViewMappingGateTest {

    private val view = "TileView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun withLine(text: String, after: String, line: String) = edit(text, after, "$after\n$line")

    private fun inView(check: (String) -> List<String>): List<String> =
        ModuleFiles.mainTexts()[view]?.let(check) ?: listOf("$view is missing from the main sources")

    // ---- The rules, each from source text to a list of violations ----

    private val endsWithRing = Regex(""",\s*(?:face\.)?look\.ring\s*,?\s*\)\z""")
    private val noticeColour = Regex("""\btextPaint\.color\s*=\s*(?:face\.)?look\.control[ \t]*(?:\n|\z)""")
    private val micCell = Regex("""\bval\s+(\w+)\s*=\s*TileLayout\.micCell\(""")
    private val cellArg = Regex("""\(\s*canvas\s*,\s*(TileLayout\.micCell\([^()]*\)|\w+)\s*,""")
    private val sideDecl = Regex("""\bval\s+(\w+)\s*=\s*if\s*\([^)]*\)\s*width\s*/\s*3\s+else\s+height\b""")
    private val ringDecl = Regex("""\bval\s+(\w+)\s*=\s*TileMetrics\.RING_DP\s*\*\s*density\b""")

    /** The calls (not the declarations) of [name] in [code], each from its name to its closing bracket. */
    private fun callsOf(code: String, name: String): List<String> =
        Regex("""\b$name\s*\(""").findAll(code).filterNot { code.substring(0, it.range.first).trimEnd().endsWith("fun") }.map { match ->
            var depth = 0
            var end = match.range.last
            while (end < code.length) {
                if (code[end] == '(') depth++
                if (code[end] == ')' && --depth == 0) break
                end++
            }
            code.substring(match.range.first, minOf(end + 1, code.length))
        }.toList()

    /**
     * The ring is drawn with look.ring and the notice text with look.control. The button part is retired: the colour of
     * each button is held by faceSpec in ViewDrawingRules (the fill) and by ControlDrawGateTest (the outline).
     */
    private fun colourRoleProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val ring = callsOf(code, "drawRing")
        return listOfNotNull(
            "the ring is not drawn with look.ring".takeUnless { ring.size == 1 && endsWithRing.containsMatchIn(ring[0]) },
            "the notice text is not drawn with look.control".takeUnless { noticeColour.containsMatchIn(code) },
        )
    }

    /** The ring is drawn into the microphone cell, which is cut once as TileLayout.micCell and never a whole window. */
    private fun cellProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val mics = micCell.findAll(code).map { it.groupValues[1] }.toSet()
        val arg = callsOf(code, "drawRing").singleOrNull()?.let { cellArg.find(it)?.groupValues?.get(1) }
        return listOfNotNull(
            "drawRing is not called once with the microphone cell".takeUnless { arg != null && (arg in mics || arg.startsWith("TileLayout.micCell(")) },
        )
    }

    /** The guards of the view: the draw stops for a side that is not positive, and the ring is as thick as RING_DP. */
    private fun guardProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val side = sideDecl.find(code)?.groupValues?.get(1)
        val ring = AdapterRules.blockBodyOf(code, "drawRing").orEmpty()
        val stroke = ringDecl.find(ring)?.groupValues?.get(1)
        return listOfNotNull(
            "the draw does not stop when the cell side is not positive".takeUnless { side != null && Regex("""\bif\s*\(\s*$side\s*<=\s*0\s*\)\s*return\b""").containsMatchIn(code) },
            "the ring is not as thick as RING_DP".takeUnless { stroke != null && Regex("""\bpaint\.strokeWidth\s*=\s*$stroke\b""").containsMatchIn(ring) },
        )
    }

    // ---- A correct view in short form, and the controls built from it ----

    private val sample = listOf(
        "internal class V {",
        "fun onDraw(canvas: Canvas) {",
        "val look = face.look",
        "val s = if (face.shape == TileShape.NOTICE) width / 3 else height",
        "if (s <= 0) return",
        "val mic = TileLayout.micCell(face.shape, s)",
        "artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density)",
        "drawRing(canvas, mic, look.ring)",
        "artDraw.drawArt(canvas, mic, paint, face, pulseAlpha)",
        "artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)",
        "artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)",
        "}",
        "private fun drawRing(canvas: Canvas, cell: TileRect, color: Int) {",
        "val stroke = TileMetrics.RING_DP * density",
        "paint.strokeWidth = stroke",
        "paint.color = color",
        "}",

        "private fun drawNotice(canvas: Canvas, s: Int, face: TileFace) {",
        "textPaint.color = face.look.control",
        "}",
        "}",
    ).joinToString("\n") + "\n"

    private val allRules = listOf(
        this::colourRoleProblems, this::cellProblems, this::guardProblems, AdapterRules::viewColourProblems,
    )

    /** A failure here means a part of the tile is painted with another part's colour, for example the ring no longer shows the state. */
    @Test
    fun `the view paints the ring and the notice text with their own colours of the look`() {
        assertQuiet("the sample, under every rule", allRules.flatMap { it(sample) })
        assertFires("the ring in the glyph colour", colourRoleProblems(edit(sample, "mic, look.ring)", "mic, look.glyph)")), "look.ring")
        assertFires("the ring in the button colour", colourRoleProblems(edit(sample, "mic, look.ring)", "mic, look.control)")), "look.ring")
        assertFires("the ring call only in a comment", colourRoleProblems(edit(sample, "drawRing(canvas, mic, look.ring)", "// drawRing(canvas, mic, look.ring)")), "look.ring")
        assertFires("the notice in a segment colour", colourRoleProblems(edit(sample, "textPaint.color = face.look.control", "textPaint.color = face.look.litSegment")), "notice text")
        assertFires("the notice in the unlit colour", colourRoleProblems(edit(sample, "textPaint.color = face.look.control", "textPaint.color = face.look.unlitSegment")), "notice text")
        assertQuiet("the look through the face, and the notice without it", colourRoleProblems(edit(edit(sample, "mic, look.ring)", "mic, face.look.ring)"), "textPaint.color = face.look.control", "textPaint.color = look.control")))
        assertQuiet("a wrong ring colour in a comment next to the right one", colourRoleProblems(edit(sample, "drawRing(canvas, mic, look.ring)", "// drawRing(canvas, mic, look.glyph)\ndrawRing(canvas, mic, look.ring)")))

        assertEquals("overlay: $view must paint the ring and the notice text with their own colours of the look", emptyList<String>(), inView(this::colourRoleProblems))
    }

    /** A failure here means the ring is drawn into a rectangle other than the microphone cell, while taps still answer in that cell. */
    @Test
    fun `the view draws the ring into the microphone cell`() {
        val whole = "TileRect(0, 0, width, height)"
        assertFires("the ring over the whole window", cellProblems(edit(sample, "drawRing(canvas, mic, look.ring)", "drawRing(canvas, $whole, look.ring)")), "drawRing")
        assertFires("the ring in a stored whole window", cellProblems(edit(edit(sample, "val mic =", "val whole = $whole\nval mic ="), "drawRing(canvas, mic,", "drawRing(canvas, whole,")), "drawRing")
        assertFires("the ring in the cancel cell", cellProblems(edit(sample, "drawRing(canvas, mic,", "drawRing(canvas, TileLayout.cancelCell(s),")), "drawRing")
        assertFires("no microphone cell at all", cellProblems(edit(sample, "val mic = TileLayout.micCell(face.shape, s)\n", "")), "drawRing")
        assertFires("the ring drawn twice", cellProblems(edit(sample, "drawRing(canvas, mic, look.ring)", "drawRing(canvas, mic, look.ring)\ndrawRing(canvas, mic, look.ring)")), "drawRing")
        val inline = edit(edit(sample, "val mic = TileLayout.micCell(face.shape, s)\n", ""), "drawRing(canvas, mic, look.ring)", "drawRing(canvas, TileLayout.micCell(face.shape, s), look.ring)")
        assertQuiet("the cell cut inside the call", cellProblems(inline))
        assertQuiet("the cell under another name", cellProblems(edit(edit(sample, "val mic =", "val cell ="), "drawRing(canvas, mic,", "drawRing(canvas, cell,")))

        assertEquals("overlay: $view must draw the ring into the microphone cell", emptyList<String>(), inView(this::cellProblems))
    }

    /** A failure here means the guard of the view or the width of the ring was dropped: a zero side draws, or the ring loses its width. */
    @Test
    fun `the view keeps its guards and the ring width`() {
        assertFires("no stop for a side of zero", guardProblems(edit(sample, "if (s <= 0) return\n", "")), "not positive")
        assertFires("the stop only for a negative side", guardProblems(edit(sample, "if (s <= 0)", "if (s < 0)")), "not positive")
        assertFires("a ring with no width", guardProblems(edit(sample, "TileMetrics.RING_DP * density", "0f * density")), "as thick as RING_DP")
        assertFires("a ring width that is not used", guardProblems(edit(sample, "paint.strokeWidth = stroke", "paint.strokeWidth = 1f")), "as thick as RING_DP")
        assertQuiet("the side under another name", guardProblems(edit(edit(sample, "val s =", "val side ="), "if (s <= 0)", "if (side <= 0)")))

        assertEquals("overlay: $view must keep its guard and the ring width", emptyList<String>(), inView(this::guardProblems))
    }

    /** A failure here means a colour can reach the canvas as plain numbers, a resource or a literal passed to a colour call. */
    @Test
    fun `the view makes no colour from numbers and passes no literal to a colour call`() {
        val after = "val mic = TileLayout.micCell(face.shape, s)"
        listOf(
            "paint.setARGB(255, 30, 122, 70)" to "a colour made from numbers",
            "canvas.drawARGB(255, 30, 122, 70)" to "a colour made from numbers",
            "canvas.drawRGB(1, 2, 3)" to "a colour made from numbers",
            "val d = ColorDrawable(look.glyph)" to "a ColorDrawable",
            "setBackgroundResource(1)" to "a background resource",
            "paint.setColor(-16711936)" to "setColor is given '-16711936'",
            "canvas.drawColor(255)" to "drawColor is given '255'",
            "paint.setColor(0xFF1E7A46.toInt())" to "a hex literal",
            "textPaint.setHighlightColor(9)" to "setHighlightColor is given '9'",
            "drawable.setTintList(list)" to "setTintList is given 'list'",
            "paint.setColor(color = 5)" to "setColor is given 'color = 5'",
            "color = 7" to "a colour is set from '7'",
        ).forEach { (line, part) -> assertFires(line, AdapterRules.viewColourProblems(withLine(sample, after, line)), part) }
        assertQuiet(
            "setters given values of the look, and calls that set no colour",
            AdapterRules.viewColourProblems(withLine(sample, after, "paint.setColor(look.glyph)\ncolor = look.glyph\nsetFilterTouchesWhenObscured(true)\n.setEllipsize(TextUtils.TruncateAt.END)")),
        )
        assertQuiet(
            "the new words and a bare colour only in comments",
            AdapterRules.viewColourProblems("// paint.setARGB(1, 2, 3, 4) canvas.drawRGB(1, 2, 3) ColorDrawable setBackgroundResource(1) color = 7\n" + sample),
        )

        assertEquals("overlay: $view must make no colour from numbers", emptyList<String>(), inView(AdapterRules::viewColourProblems))
    }
}
