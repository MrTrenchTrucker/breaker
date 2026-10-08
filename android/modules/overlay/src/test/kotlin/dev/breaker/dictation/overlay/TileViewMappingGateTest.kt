package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the tile view paints each part with and where: which colour of the look colours which part,
 * which rectangle the glyph and the ring are drawn into, how many strokes the cross and the check
 * are made of, and the guards and insets of the meter and the ring. The view cannot run on a plain
 * JVM, so these rules read its source text.
 *
 * Every test runs its rule first on short made-up samples (a wrong line must be reported with the
 * right words, a correct one must not, even with other names) and only then on the real file. A
 * construct that cannot be found is a violation, never a pass. Comments are blanked, strings are kept.
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

    private val roleBranch = Regex("""\bGlyphRole\.(\w+)\s*->\s*(?:face\.)?look\.(\w+)""")
    private val wantedRoles = mapOf("BODY" to "glyph", "GRILLE" to "glyphOutline", "OUTLINE" to "glyphOutline")
    private val baseFill = Regex("""\bpaint\.color\s*=\s*(?:face\.)?look\.background\s+canvas\.drawRoundRect\(\s*0f\s*,\s*0f\s*,\s*width\.toFloat\(\)\s*,\s*height\.toFloat\(\)""")
    private val noticeColour = Regex("""\btextPaint\.color\s*=\s*(?:face\.)?look\.control[ \t]*(?:\n|\z)""")
    private val endsWithRing = Regex(""",\s*(?:face\.)?look\.ring\s*,?\s*\)\z""")
    private val endsWithControl = Regex(""",\s*(?:face\.)?look\.control\s*,?\s*\)\z""")
    private val micCell = Regex("""\bval\s+(\w+)\s*=\s*TileLayout\.micCell\(""")
    private val cellArg = Regex("""\(\s*canvas\s*,\s*(TileLayout\.micCell\([^()]*\)|\w+)\s*,""")
    private val sideDecl = Regex("""\bval\s+(\w+)\s*=\s*if\s*\([^)]*\)\s*height\s+else\s+width\s*/\s*3\b""")
    private val gapDecl = Regex("""\bval\s+(\w+)\s*=\s*TileMetrics\.SEGMENT_GAP_PX\b""")
    private val ringDecl = Regex("""\bval\s+(\w+)\s*=\s*TileMetrics\.RING_DP\s*\*\s*density\b""")
    private val tooSmall = Regex("""\bif\s*\(\s*\w+\s*<=\s*\w+\s*\|\|\s*\w+\s*<=\s*\w+\s*\)\s*continue\b""")

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

    private fun colourRoleProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val roles = roleBranch.findAll(code).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val ring = callsOf(code, "drawRing")
        val buttons = callsOf(code, "drawCancel") + callsOf(code, "drawSend")
        return wantedRoles.mapNotNull { (role, field) ->
            "the glyph part $role is not drawn with look.$field".takeUnless { roles.filter { it.first == role }.let { found -> found.size == 1 && found[0].second == field } }
        } + listOfNotNull(
            "the base is not filled with look.background".takeUnless { baseFill.containsMatchIn(code) },
            "the ring is not drawn with look.ring".takeUnless { ring.size == 1 && endsWithRing.containsMatchIn(ring[0]) },
            "a button is not drawn with look.control".takeUnless { buttons.size == 2 && buttons.all { endsWithControl.containsMatchIn(it) } },
            "the notice text is not drawn with look.control".takeUnless { noticeColour.containsMatchIn(code) },
        )
    }

    private fun cellProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val mics = micCell.findAll(code).map { it.groupValues[1] }.toSet()
        return listOf("drawGlyph", "drawRing").mapNotNull { name ->
            val arg = callsOf(code, name).singleOrNull()?.let { cellArg.find(it)?.groupValues?.get(1) }
            "$name is not called once with the microphone cell".takeUnless { arg != null && (arg in mics || arg.startsWith("TileLayout.micCell(")) }
        }
    }

    private fun strokeProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return listOf("drawCancel" to "cross", "drawSend" to "check mark").flatMap { (name, picture) ->
            val body = AdapterRules.blockBodyOf(code, name)
            val lines = body?.let { callsOf(it, "drawLine") }.orEmpty().map { line -> line.filterNot { it.isWhitespace() } }
            listOfNotNull(
                "$name does not draw the $picture as two different strokes".takeUnless { lines.size == 2 && lines[0] != lines[1] },
                "$name does not set up its stroke with strokeControl(color)".takeUnless { body != null && Regex("""\bstrokeControl\s*\(\s*color\s*\)""").containsMatchIn(body) },
            )
        }
    }

    private fun insetProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val side = sideDecl.find(code)?.groupValues?.get(1)
        val meter = AdapterRules.blockBodyOf(code, "drawMeter").orEmpty()
        val ring = AdapterRules.blockBodyOf(code, "drawRing").orEmpty()
        val gap = gapDecl.find(meter)?.groupValues?.get(1)
        val stroke = ringDecl.find(ring)?.groupValues?.get(1)
        val edges = listOf("left" to "\\+", "top" to "\\+", "right" to "-", "bottom" to "-")
        return listOfNotNull(
            "the draw does not stop when the cell side is not positive".takeUnless { side != null && Regex("""\bif\s*\(\s*$side\s*<=\s*0\s*\)\s*return\b""").containsMatchIn(code) },
            "a meter segment too small for its gap is not skipped".takeUnless { tooSmall.containsMatchIn(meter) },
            "the meter segments are not inset by the gap on all four edges".takeUnless {
                gap != null && edges.all { (edge, sign) -> Regex("""\bval\s+\w+\s*=\s*\w+\.$edge\s*$sign\s*$gap\b""").containsMatchIn(meter) }
            },
            "the ring is not as thick as RING_DP".takeUnless { stroke != null && Regex("""\bpaint\.strokeWidth\s*=\s*$stroke\b""").containsMatchIn(ring) },
        )
    }

    // ---- A correct view in short form, and the controls built from it ----

    private val sample = listOf(
        "internal class V {",
        "fun onDraw(canvas: Canvas) {",
        "val look = face.look",
        "val s = if (face.shape == TileShape.COLLAPSED) height else width / 3",
        "if (s <= 0) return",
        "paint.color = look.background",
        "canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)",
        "val mic = TileLayout.micCell(face.shape, s)",
        "drawGlyph(canvas, mic, look)",
        "drawRing(canvas, mic, look.ring)",
        "drawCancel(canvas, TileLayout.cancelCell(s), look.control)",
        "drawSend(canvas, TileLayout.sendCell(s), look.control)",
        "}",
        "private fun drawGlyph(canvas: Canvas, cell: TileRect, look: TileLook) {",
        "paint.color = when (rect.role) {",
        "GlyphRole.BODY -> look.glyph",
        "GlyphRole.GRILLE -> look.glyphOutline",
        "GlyphRole.OUTLINE -> look.glyphOutline",
        "}",
        "}",
        "private fun drawRing(canvas: Canvas, cell: TileRect, color: Int) {",
        "val stroke = TileMetrics.RING_DP * density",
        "paint.strokeWidth = stroke",
        "paint.color = color",
        "}",
        "private fun drawMeter(canvas: Canvas, s: Int, face: TileFace) {",
        "val gap = TileMetrics.SEGMENT_GAP_PX.toFloat()",
        "for ((index, cell) in cells) {",
        "val left = cell.left + gap",
        "val top = cell.top + gap",
        "val right = cell.right - gap",
        "val bottom = cell.bottom - gap",
        "if (right <= left || bottom <= top) continue",
        "}",
        "}",
        "private fun drawCancel(canvas: Canvas, cell: TileRect, color: Int) {",
        "strokeControl(color)",
        "canvas.drawLine(left, top, right, bottom, paint)",
        "canvas.drawLine(right, top, left, bottom, paint)",
        "}",
        "private fun drawSend(canvas: Canvas, cell: TileRect, color: Int) {",
        "strokeControl(color)",
        "canvas.drawLine(a, b, footX, footY, paint)",
        "canvas.drawLine(footX, footY, c, d, paint)",
        "}",
        "private fun strokeControl(color: Int) {",
        "paint.color = color",
        "}",
        "private fun drawNotice(canvas: Canvas, s: Int, face: TileFace) {",
        "textPaint.color = face.look.control",
        "}",
        "}",
    ).joinToString("\n") + "\n"

    private val allRules = listOf(
        this::colourRoleProblems, this::cellProblems, this::strokeProblems, this::insetProblems, AdapterRules::viewColourProblems,
    )

    /** A failure here means a part of the tile is painted with another part's colour, for example the ring no longer shows the state. */
    @Test
    fun `the view paints each part of the tile with its own colour of the look`() {
        assertQuiet("the sample, under every rule", allRules.flatMap { it(sample) })
        assertFires("the ring in the glyph colour", colourRoleProblems(edit(sample, "mic, look.ring)", "mic, look.glyph)")), "look.ring")
        assertFires("the ring in the button colour", colourRoleProblems(edit(sample, "mic, look.ring)", "mic, look.control)")), "look.ring")
        assertFires("the ring call only in a comment", colourRoleProblems(edit(sample, "drawRing(canvas, mic, look.ring)", "// drawRing(canvas, mic, look.ring)")), "look.ring")
        assertFires("the base in the button colour", colourRoleProblems(edit(sample, "paint.color = look.background", "paint.color = look.control")), "look.background")
        assertFires("no base fill", colourRoleProblems(edit(sample, "canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)\n", "")), "look.background")
        assertFires("the notice in a meter colour", colourRoleProblems(edit(sample, "textPaint.color = face.look.control", "textPaint.color = face.look.litSegment")), "notice text")
        assertFires("the notice in the unlit colour", colourRoleProblems(edit(sample, "textPaint.color = face.look.control", "textPaint.color = face.look.unlitSegment")), "notice text")
        assertFires("the cross in the glyph colour", colourRoleProblems(edit(sample, "cancelCell(s), look.control)", "cancelCell(s), look.glyph)")), "a button")
        assertFires("the check in the glyph colour", colourRoleProblems(edit(sample, "sendCell(s), look.control)", "sendCell(s), look.glyph)")), "a button")
        assertFires("no check", colourRoleProblems(edit(sample, "drawSend(canvas, TileLayout.sendCell(s), look.control)\n", "")), "a button")
        assertFires("the body in the outline colour", colourRoleProblems(edit(sample, "BODY -> look.glyph\n", "BODY -> look.glyphOutline\n")), "part BODY")
        assertFires("the grille in the body colour", colourRoleProblems(edit(sample, "GRILLE -> look.glyphOutline", "GRILLE -> look.glyph")), "part GRILLE")
        assertFires("the outline in the body colour", colourRoleProblems(edit(sample, "OUTLINE -> look.glyphOutline", "OUTLINE -> look.glyph")), "part OUTLINE")
        assertFires("no branch for the grille", colourRoleProblems(edit(sample, "GlyphRole.GRILLE -> look.glyphOutline\n", "")), "part GRILLE")
        assertQuiet("the look through the face, and the notice without it", colourRoleProblems(edit(edit(sample, "mic, look.ring)", "mic, face.look.ring)"), "textPaint.color = face.look.control", "textPaint.color = look.control")))
        assertQuiet("the arguments on separate lines with a trailing comma", colourRoleProblems(edit(sample, "drawCancel(canvas, TileLayout.cancelCell(s), look.control)", "drawCancel(\n canvas,\n TileLayout.cancelCell(s),\n look.control,\n)")))
        assertQuiet("a role through the face, and a wrong line in a comment", colourRoleProblems(edit(edit(sample, "BODY -> look.glyph\n", "BODY -> face.look.glyph\n"), "drawRing(canvas, mic, look.ring)", "// drawRing(canvas, mic, look.glyph)\ndrawRing(canvas, mic, look.ring)")))

        assertEquals("overlay: $view must paint each part with its own colour of the look", emptyList<String>(), inView(this::colourRoleProblems))
    }

    /** A failure here means the glyph or the ring is drawn into a rectangle other than the microphone cell, while taps still answer in that cell. */
    @Test
    fun `the view draws the glyph and the ring into the microphone cell`() {
        val whole = "TileRect(0, 0, width, height)"
        assertFires("the glyph over the whole window", cellProblems(edit(sample, "drawGlyph(canvas, mic, look)", "drawGlyph(canvas, $whole, look)")), "drawGlyph")
        assertFires("the ring over the whole window", cellProblems(edit(sample, "drawRing(canvas, mic, look.ring)", "drawRing(canvas, $whole, look.ring)")), "drawRing")
        assertFires("the glyph in a stored whole window", cellProblems(edit(edit(sample, "val mic =", "val whole = $whole\nval mic ="), "(canvas, mic, look)", "(canvas, whole, look)")), "drawGlyph")
        assertFires("the ring in the cancel cell", cellProblems(edit(sample, "drawRing(canvas, mic,", "drawRing(canvas, TileLayout.cancelCell(s),")), "drawRing")
        assertFires("no microphone cell at all", cellProblems(edit(sample, "val mic = TileLayout.micCell(face.shape, s)\n", "")), "drawGlyph")
        assertFires("the glyph drawn twice", cellProblems(edit(sample, "drawGlyph(canvas, mic, look)", "drawGlyph(canvas, mic, look)\ndrawGlyph(canvas, mic, look)")), "drawGlyph")
        val inline = edit(edit(edit(sample, "val mic = TileLayout.micCell(face.shape, s)\n", ""), "(canvas, mic, look)", "(canvas, TileLayout.micCell(face.shape, s), look)"), "(canvas, mic, look.ring)", "(canvas, TileLayout.micCell(face.shape, s), look.ring)")
        assertQuiet("the cell cut inside the calls", cellProblems(inline))
        assertQuiet("the cell under another name", cellProblems(edit(edit(edit(sample, "val mic =", "val cell ="), "(canvas, mic, look)", "(canvas, cell, look)"), "(canvas, mic, look.ring)", "(canvas, cell, look.ring)")))

        assertEquals("overlay: $view must draw the glyph and the ring into the microphone cell", emptyList<String>(), inView(this::cellProblems))
    }

    /** A failure here means the cross or the check is no longer made of two different strokes, so a button is drawn as a bare slash. */
    @Test
    fun `the view draws the cross and the check as two strokes each`() {
        val xFirst = "canvas.drawLine(left, top, right, bottom, paint)"
        val xSecond = "canvas.drawLine(right, top, left, bottom, paint)"
        val checkFirst = "canvas.drawLine(a, b, footX, footY, paint)"
        val checkSecond = "canvas.drawLine(footX, footY, c, d, paint)"
        assertFires("the second stroke of the cross removed", strokeProblems(edit(sample, "$xSecond\n", "")), "drawCancel does not draw the cross")
        assertFires("the first stroke of the cross removed", strokeProblems(edit(sample, "$xFirst\n", "")), "drawCancel does not draw the cross")
        assertFires("the cross drawn as one stroke twice", strokeProblems(edit(sample, xSecond, xFirst)), "drawCancel does not draw the cross")
        assertFires("the second stroke of the check removed", strokeProblems(edit(sample, "$checkSecond\n", "")), "drawSend does not draw the check mark")
        assertFires("the first stroke of the check removed", strokeProblems(edit(sample, "$checkFirst\n", "")), "drawSend does not draw the check mark")
        assertFires("a third stroke on the check", strokeProblems(edit(sample, checkSecond, "$checkSecond\n$xFirst")), "drawSend does not draw the check mark")
        assertFires("a stroke only in a comment", strokeProblems(edit(sample, checkSecond, "// $checkSecond")), "drawSend does not draw the check mark")
        assertFires("no stroke set-up for the check", strokeProblems(edit(sample, "color: Int) {\nstrokeControl(color)\n$checkFirst", "color: Int) {\n$checkFirst")), "drawSend does not set up")
        assertFires("no cross function", strokeProblems(edit(sample, "private fun drawCancel(", "private fun drawCancelled(")), "drawCancel does not draw")
        assertQuiet("the strokes with their arguments on separate lines", strokeProblems(edit(sample, xFirst, "canvas.drawLine(\n left,\n top,\n right,\n bottom,\n paint,\n)")))
        assertQuiet("a comment between the two strokes", strokeProblems(edit(sample, xSecond, "// back stroke\n$xSecond")))

        assertEquals("overlay: $view must draw the cross and the check as two different strokes each", emptyList<String>(), inView(this::strokeProblems))
    }

    /** A failure here means a guard or an inset of the meter or the ring was dropped: segments touch, a tiny segment draws backwards, or the ring loses its width. */
    @Test
    fun `the view keeps its guards and the gap between meter segments and the ring width`() {
        assertFires("no stop for a side of zero", insetProblems(edit(sample, "if (s <= 0) return\n", "")), "not positive")
        assertFires("the stop only for a negative side", insetProblems(edit(sample, "if (s <= 0)", "if (s < 0)")), "not positive")
        assertFires("a too small segment is drawn", insetProblems(edit(sample, "if (right <= left || bottom <= top) continue\n", "")), "too small")
        assertFires("no gap at the left", insetProblems(edit(sample, "cell.left + gap", "cell.left + 0f")), "inset by the gap")
        assertFires("no gap at the top", insetProblems(edit(sample, "cell.top + gap", "cell.top + 0f")), "inset by the gap")
        assertFires("the gap added at the right", insetProblems(edit(sample, "cell.right - gap", "cell.right + gap")), "inset by the gap")
        assertFires("no gap at the bottom", insetProblems(edit(sample, "cell.bottom - gap", "cell.bottom - 0f")), "inset by the gap")
        assertFires("a gap that is not the named size", insetProblems(edit(sample, "TileMetrics.SEGMENT_GAP_PX.toFloat()", "1f")), "inset by the gap")
        assertFires("a ring with no width", insetProblems(edit(sample, "TileMetrics.RING_DP * density", "0f * density")), "as thick as RING_DP")
        assertFires("a ring width that is not used", insetProblems(edit(sample, "paint.strokeWidth = stroke", "paint.strokeWidth = 1f")), "as thick as RING_DP")
        assertQuiet("the gap under another name", insetProblems(edit(sample, "gap", "inset")))
        assertQuiet("the skip with other names", insetProblems(edit(sample, "if (right <= left || bottom <= top) continue", "if (b <= a || d <= c) continue")))

        assertEquals("overlay: $view must keep its guards, the segment gap and the ring width", emptyList<String>(), inView(this::insetProblems))
    }

    /** A failure here means a colour can reach the canvas as plain numbers, a resource or a literal passed to a colour call. */
    @Test
    fun `the view makes no colour from numbers and passes no literal to a colour call`() {
        val after = "paint.color = look.background"
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
