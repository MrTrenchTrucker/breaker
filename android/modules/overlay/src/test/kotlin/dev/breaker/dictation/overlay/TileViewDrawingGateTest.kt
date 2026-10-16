package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawing rules of the tile view, checked on its source text because the view cannot run on a plain JVM:
 * which side the cells are cut from, which cell holds which control and how often, which branch draws what,
 * how the notice is wrapped and cut, and (in the art) which grille slots are lit and how they are coloured.
 *
 * Every test runs its rule first on short made-up samples (a flipped line must be reported with the right words,
 * a correct one must not, even with other names) and only then on the real file. A construct that cannot be found
 * is a violation, never a pass. Comments are blanked, strings are kept.
 */
class TileViewDrawingGateTest {

    private val view = "TileView.kt"
    private val art = "TileArtDraw.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun inView(check: (String) -> List<String>): List<String> =
        ModuleFiles.mainTexts()[view]?.let(check) ?: listOf("$view is missing from the main sources")

    // ---- The rules, each from source text to a list of violations ----

    private val sideDecl = Regex("""\bval\s+(\w+)\s*=\s*if\s*\(\s*(?:this\.)?(?:face\.)?shape\s*==\s*TileShape\.COLLAPSED\s*\)\s*height\s+else\s+width\s*/\s*3\b""")
    private val micCall = Regex("""TileLayout\.micCell\(\s*(?:this\.)?(?:face\.)?shape\s*,\s*(\w+)\s*\)""")
    private val cancelControl = Regex("""\bdrawControl\s*\(\s*canvas\s*,\s*TileLayout\.cancelCell\(\s*(\w+)\s*\)\s*,\s*paint\s*,\s*ControlArt\.cancel\s*,""")
    private val sendControl = Regex("""\bdrawControl\s*\(\s*canvas\s*,\s*TileLayout\.sendCell\(\s*(\w+)\s*\)\s*,\s*paint\s*,\s*ControlArt\.send\s*,""")
    private val recordingHead = Regex("""TileShape\.RECORDING\s*->\s*\{""")
    private val noticeBranch = Regex("""TileShape\.NOTICE\s*->\s*drawNotice\s*\(""")
    private val widthOf = Regex("""\bval\s+(\w+)\s*=\s*(\w+)\.width\b""")
    private val noticeArea = Regex("""\bval\s+(\w+)\s*=\s*TileLayout\.noticeRect\(\s*(\w+)\s*\)""")
    private val layoutWidth = Regex("""StaticLayout\.Builder\.obtain\(\s*[^,]+,\s*[^,]+,\s*[^,]+,\s*[^,]+,\s*(\w+)\s*\)""")
    private val maxLines = Regex("""\.setMaxLines\(\s*(\w+)\s*\)""")
    private val ellipsize = Regex("""\.setEllipsize\(\s*TextUtils\.TruncateAt\.END\s*\)""")
    private val constNumber = Regex("""\bconst\s+val\s+(\w+)\s*(?::\s*\w+\s*)?=\s*(\d+)\b""")

    private fun sideOf(code: String): String? = sideDecl.find(code)?.groupValues?.get(1)

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

    private fun sideProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val side = sideOf(code)
        val mics = micCall.findAll(code).map { it.groupValues[1] }.toList()
        return listOfNotNull(
            "the cell side is not height for a collapsed tile and width / 3 otherwise".takeIf { side == null },
            "the microphone cell is not cut for the face's shape from the cell side".takeIf { mics.isEmpty() || mics.any { it != side } },
        )
    }

    private fun recordingBranch(code: String): String? {
        val head = recordingHead.find(code) ?: return null
        val close = SourceText.closeOf(code, head.range.last)
        return if (close < 0) null else code.substring(head.range.last, close + 1)
    }

    /**
     * Each button is drawn once by drawControl, with its cell cut from the side of the cell: the cross in the cancel
     * cell and the check in the send cell. The recording branch holds the two calls, and the notice branch draws the
     * notice. The fill, the outline and the colours are checked by the button rules.
     */
    private fun controlProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val side = sideOf(code)
        val controls = callsOf(code, "drawControl")
        val cancel = controls.filter { cancelControl.containsMatchIn(it) }
        val send = controls.filter { sendControl.containsMatchIn(it) }
        val recording = recordingBranch(code)
        return listOfNotNull(
            "the cross is not drawn once into the cancel cell".takeUnless { cancel.size == 1 && cancelControl.find(cancel[0])?.groupValues?.get(1) == side },
            "the check is not drawn once into the send cell".takeUnless { send.size == 1 && sendControl.find(send[0])?.groupValues?.get(1) == side },
            "the recording branch does not draw the two controls, one call each".takeUnless {
                recording != null && Regex("""\bdrawControl\s*\(""").findAll(recording).count() == 2
            },
            "the notice branch does not draw the notice".takeUnless { noticeBranch.containsMatchIn(code) },
        )
    }

    private fun noticeProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val side = sideOf(code)
        val numbers = constNumber.findAll(code).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val limit = maxLines.find(code)?.groupValues?.get(1)?.let { it.toIntOrNull() ?: numbers[it] }
        val area = noticeArea.find(code)
        val width = widthOf.findAll(code).firstOrNull { area != null && it.groupValues[2] == area.groupValues[1] }?.groupValues?.get(1)
        val wrapped = layoutWidth.find(code)?.groupValues?.get(1)
        return listOfNotNull(
            "the notice is not limited to one or two lines".takeUnless { limit != null && limit in 1..2 },
            "the notice is not cut with an ellipsis at the end".takeUnless { ellipsize.containsMatchIn(code) },
            "the notice area is not the strip cut from the cell side".takeUnless { area != null && area.groupValues[2] == side },
            "the notice is not wrapped to the width of the notice area".takeUnless { width != null && wrapped == width },
        )
    }

    // ---- A correct view in short form, and the controls built from it ----

    private val sample = listOf(
        "internal class V {",
        "fun onDraw(canvas: Canvas) {",
        "val face = this.face",
        "val s = if (face.shape == TileShape.COLLAPSED) height else width / 3",
        "val mic = TileLayout.micCell(face.shape, s)",
        "when (face.shape) {",
        "TileShape.COLLAPSED -> Unit",
        "TileShape.NOTICE -> drawNotice(canvas, s, face)",
        "TileShape.RECORDING -> {",
        "artDraw.drawControl(canvas, TileLayout.cancelCell(s), paint, ControlArt.cancel, look)",
        "artDraw.drawControl(canvas, TileLayout.sendCell(s), paint, ControlArt.send, look)",
        "}",
        "}",
        "}",
        "private fun drawNotice(canvas: Canvas, s: Int, face: TileFace) {",
        "val area = TileLayout.noticeRect(s)",
        "val textWidth = area.width - 2 * pad",
        "val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)",
        ".setMaxLines(NOTICE_MAX_LINES)",
        ".setEllipsize(TextUtils.TruncateAt.END)",
        ".build()",
        "}",
        "private companion object {",
        "const val NOTICE_MAX_LINES = 2",
        "}",
        "}",
    ).joinToString("\n") + "\n"

    private val allRules = listOf(this::sideProblems, this::controlProblems, this::noticeProblems)

    /** A failure here means the picture and the touch zones are cut from different sides, so a button is drawn where it does not answer. */
    @Test
    fun `the view cuts its cells from the height when square and a third of the width when wide`() {
        assertQuiet("the sample, under every rule", allRules.flatMap { it(sample) })
        assertFires("a wide window cut from the height", sideProblems(edit(sample, "height else width / 3", "height else height")), "cell side")
        assertFires("a square tile cut from the width", sideProblems(edit(sample, "COLLAPSED) height else", "COLLAPSED) width / 3 else")), "cell side")
        assertFires("the test reversed", sideProblems(edit(sample, "== TileShape.COLLAPSED", "!= TileShape.COLLAPSED")), "cell side")
        assertFires("a half instead of a third", sideProblems(edit(sample, "width / 3", "width / 2")), "cell side")
        assertFires("the side line only in a comment", sideProblems("// " + sample.lines()[3] + "\n"), "cell side")
        assertFires("the microphone cell of the square shape always", sideProblems(edit(sample, "micCell(face.shape, s)", "micCell(TileShape.COLLAPSED, s)")), "microphone cell")
        assertFires("the microphone cell with another side", sideProblems(edit(sample, "micCell(face.shape, s)", "micCell(face.shape, height)")), "microphone cell")
        assertFires("no microphone cell", sideProblems(edit(sample, "val mic = TileLayout.micCell(face.shape, s)\n", "")), "microphone cell")
        assertQuiet("the side under another name", sideProblems(edit(edit(sample, "val s =", "val side ="), "(face.shape, s)", "(face.shape, side)")))
        assertQuiet("the shape through this, and the line broken", sideProblems(edit(sample, "val s = if (face.shape ==", "val s = if (this.face.shape\n ==")))

        assertEquals("overlay: $view must cut its cells from height when square and width / 3 when wide", emptyList<String>(), inView(this::sideProblems))
    }

    /**
     * A failure here means the meter shows the wrong part of the sound level. The meter is now the six grille slots
     * of the art: the slots are walked in order, each is lit by slotLit with the face's lit count, and its body and
     * its strip are coloured by slotColor. This re-points the old meter rule to the art's drawing of the slots.
     */
    @Test
    fun `the art lights the grille slots below the lit count in the lit colour`() {
        val real = ModuleFiles.mainTexts().getValue(art)
        assertQuiet("the real art", ViewDrawingRules.slotProblems(real))
        assertFires("the lit test replaced by the index compare", ViewDrawingRules.slotProblems(edit(real, "slotLit(face.state, index, face.litSegments)", "index >= face.litSegments")), "slotLit")
        assertFires("the lit test given no lit count", ViewDrawingRules.slotProblems(edit(real, "slotLit(face.state, index, face.litSegments)", "slotLit(face.state, index, 0)")), "slotLit")
        assertFires("the strip in the body colour", ViewDrawingRules.slotProblems(edit(real, "fillShape(canvas, paint, slot.top, slotColor(true, lit, look))", "fillShape(canvas, paint, slot.top, slotColor(false, lit, look))")), "strip")
        assertFires("the body in the strip colour", ViewDrawingRules.slotProblems(edit(real, "fillShape(canvas, paint, slot.body, slotColor(false, lit, look))", "fillShape(canvas, paint, slot.body, slotColor(true, lit, look))")), "body")
        assertFires("the slots not taken from TileArt.slots", ViewDrawingRules.slotProblems(edit(real, "TileArt.slots.withIndex()", "TileArt.bodyShapes.withIndex()")), "walk TileArt.slots")
        assertQuiet("the index under another name", ViewDrawingRules.slotProblems(edit(edit(real, "(index, slot) in", "(i, slot) in"), "slotLit(face.state, index,", "slotLit(face.state, i,")))

        assertEquals("overlay: $art must light the grille slots below the lit count in the lit colour", emptyList<String>(), ViewDrawingRules.slotProblems(real))
    }

    /** A failure here means a button is drawn into a cell that does not answer it, drawn more or fewer than once, or drawn outside the recording branch. */
    @Test
    fun `the view draws the cancel cross left and the check right and only when recording`() {
        val cancel = "artDraw.drawControl(canvas, TileLayout.cancelCell(s), paint, ControlArt.cancel, look)"
        val send = "artDraw.drawControl(canvas, TileLayout.sendCell(s), paint, ControlArt.send, look)"
        assertFires("the cross in the send cell", controlProblems(edit(sample, cancel, "artDraw.drawControl(canvas, TileLayout.sendCell(s), paint, ControlArt.cancel, look)")), "the cross")
        assertFires("the check in the cancel cell", controlProblems(edit(sample, send, "artDraw.drawControl(canvas, TileLayout.cancelCell(s), paint, ControlArt.send, look)")), "the check")
        assertFires("the cross with the check shape", controlProblems(edit(sample, cancel, "artDraw.drawControl(canvas, TileLayout.cancelCell(s), paint, ControlArt.send, look)")), "the cross")
        assertFires("the cross in the microphone cell", controlProblems(edit(sample, cancel, "artDraw.drawControl(canvas, TileLayout.micCell(face.shape, s), paint, ControlArt.cancel, look)")), "the cross")
        assertFires("the cross drawn three times", controlProblems(edit(sample, cancel, "$cancel\n$cancel")), "the cross")
        assertFires("no check", controlProblems(edit(sample, "$send\n", "")), "the check")
        assertFires("the cross in a cell cut from another side", controlProblems(edit(sample, "cancelCell(s)", "cancelCell(height)")), "the cross")
        assertFires("the check outside the recording branch", controlProblems(edit(edit(sample, "$send\n", ""), "TileShape.COLLAPSED -> Unit", "TileShape.COLLAPSED -> $send")), "recording branch")
        assertFires("the notice in the wrong branch", controlProblems(edit(sample, "TileShape.NOTICE -> drawNotice(canvas, s, face)", "TileShape.NOTICE -> Unit")), "notice branch")
        assertFires("no recording branch", controlProblems(edit(sample, "TileShape.RECORDING -> {", "TileShape.RECORDING -> run {")), "recording branch")
        assertQuiet("the side under another name", controlProblems(edit(edit(edit(sample, "val s =", "val side ="), "cancelCell(s)", "cancelCell(side)"), "sendCell(s)", "sendCell(side)")))
        assertQuiet("the arguments on separate lines", controlProblems(edit(sample, cancel, "artDraw.drawControl(\n canvas,\n TileLayout.cancelCell(s),\n paint,\n ControlArt.cancel,\n look,\n)")))

        assertEquals("overlay: $view must draw the cross in the cancel cell and the check in the send cell, only when recording", emptyList<String>(), inView(this::controlProblems))
    }

    /** A failure here means a long notice runs over the strip: it is not wrapped to the strip, not limited to two lines or not cut with an ellipsis. */
    @Test
    fun `the view wraps the notice to the strip in at most two lines and cuts it with an ellipsis`() {
        assertFires("no line limit", noticeProblems(edit(sample, ".setMaxLines(NOTICE_MAX_LINES)\n", "")), "one or two lines")
        assertFires("a limit of three", noticeProblems(edit(sample, "NOTICE_MAX_LINES = 2", "NOTICE_MAX_LINES = 3")), "one or two lines")
        assertFires("a limit of zero", noticeProblems(edit(sample, "setMaxLines(NOTICE_MAX_LINES)", "setMaxLines(0)")), "one or two lines")
        assertFires("a limit from a name that has no number", noticeProblems(edit(sample, "const val NOTICE_MAX_LINES = 2", "val NOTICE_MAX_LINES = 2")), "one or two lines")
        assertFires("the cut at the start", noticeProblems(edit(sample, "TruncateAt.END", "TruncateAt.START")), "ellipsis")
        assertFires("no ellipsis", noticeProblems(edit(sample, ".setEllipsize(TextUtils.TruncateAt.END)\n", "")), "ellipsis")
        assertFires("wrapped to the whole window", noticeProblems(edit(sample, "textPaint, textWidth)", "textPaint, width)")), "wrapped to the width")
        assertFires("a width taken from the height", noticeProblems(edit(sample, "area.width - 2 * pad", "area.height - 2 * pad")), "wrapped to the width")
        assertFires("the area cut from another side", noticeProblems(edit(sample, "noticeRect(s)", "noticeRect(height)")), "notice area")
        assertQuiet("a limit of one, and a number in the call", noticeProblems(edit(sample, "setMaxLines(NOTICE_MAX_LINES)", "setMaxLines(1)")))
        assertQuiet("another name for the width and the area", noticeProblems(edit(edit(sample, "textWidth", "w"), "area", "strip")))

        assertEquals("overlay: $view must wrap the notice to the strip, at most two lines, cut with an ellipsis", emptyList<String>(), inView(this::noticeProblems))
    }
}
