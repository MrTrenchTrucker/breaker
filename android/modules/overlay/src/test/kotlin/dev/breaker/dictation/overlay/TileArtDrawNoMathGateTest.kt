package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The art drawing holds no geometry of its own. TileArtDraw.kt names none of the busy-sign margin, the busy stroke
 * over two, the art fill or the glow step, and it takes no square root: those numbers and that arithmetic live in
 * TileArtGeometry.kt, which has no android names. Comments are blanked and string literals are kept, as in
 * [SourceText.code]. The rule runs first on edited copies of a sample of the busy sign and the cell frame, then on
 * the real file.
 */
class TileArtDrawNoMathGateTest {

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

    private val banned = listOf(
        "sqrt" to Regex("\\bsqrt\\b"),
        "BUSY_MARGIN_UNITS" to Regex("\\bBUSY_MARGIN_UNITS\\b"),
        "BUSY_STROKE_UNITS /" to Regex("\\bBUSY_STROKE_UNITS\\s*/"),
        "ART_FILL" to Regex("\\bART_FILL\\b"),
        "GLOW_STEP_DP" to Regex("\\bGLOW_STEP_DP\\b"),
    )

    /** The banned words that the code of [source] holds, one problem each; a word in a comment is not counted. */
    private fun mathProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return banned.filter { (_, pattern) -> pattern.containsMatchIn(code) }.map { (word, _) -> "TileArtDraw.kt holds $word" }
    }

    /** The cell frame and the busy sign as the adapter draws them after the change. */
    private val drawSample = "private inline fun inCell(canvas: Canvas, cell: TileRect, draw: (Float) -> Unit) {\n" +
        "val frame = ArtGeometry.frame(cell)\n" +
        "canvas.save()\n" +
        "canvas.translate(frame.centreX, frame.centreY)\n" +
        "canvas.scale(frame.scale, frame.scale)\n" +
        "canvas.translate(frame.originX, frame.originY)\n" +
        "draw(frame.scale)\n" +
        "canvas.restore()\n" +
        "}\n" +
        "private fun drawBusy(canvas: Canvas, paint: Paint, color: Int, pulseAlpha: Float) {\n" +
        "val busy = ArtGeometry.busySign()\n" +
        "canvas.drawCircle(busy.centre, busy.centre, busy.radius, paint)\n" +
        "canvas.drawLine(busy.slashFromX, busy.slashFromY, busy.slashToX, busy.slashToY, paint)\n" +
        "}\n"

    /** A failure means the art drawing holds a busy-sign number, the art fill, the glow step or a square root, which belong to the geometry file. */
    @Test
    fun `the art drawing holds no geometry number and no square root`() {
        assertQuiet("the sample", mathProblems(drawSample))
        assertFires("an added square root line", mathProblems(edit(drawSample, "val busy = ArtGeometry.busySign()\n", "val busy = ArtGeometry.busySign()\nval reach = busy.radius / sqrt(2f)\n")), "holds sqrt")
        assertFires("an added art fill line", mathProblems(edit(drawSample, "val frame = ArtGeometry.frame(cell)\n", "val frame = ArtGeometry.frame(cell)\nval fill = ArtMetrics.ART_FILL\n")), "holds ART_FILL")
        assertFires("an added busy margin", mathProblems(edit(drawSample, "val busy = ArtGeometry.busySign()\n", "val busy = ArtGeometry.busySign()\nval gap = ArtMetrics.BUSY_MARGIN_UNITS\n")), "holds BUSY_MARGIN_UNITS")
        assertFires("an added busy stroke division", mathProblems(edit(drawSample, "val busy = ArtGeometry.busySign()\n", "val busy = ArtGeometry.busySign()\nval half = ArtMetrics.BUSY_STROKE_UNITS / 2f\n")), "holds BUSY_STROKE_UNITS /")
        assertFires("an added glow step", mathProblems(edit(drawSample, "val busy = ArtGeometry.busySign()\n", "val busy = ArtGeometry.busySign()\nval step = ArtMetrics.GLOW_STEP_DP\n")), "holds GLOW_STEP_DP")
        assertQuiet("the words only in comments", mathProblems(edit(drawSample, "val busy = ArtGeometry.busySign()\n", "val busy = ArtGeometry.busySign()\n// sqrt(2f) ART_FILL BUSY_MARGIN_UNITS GLOW_STEP_DP\n/* BUSY_STROKE_UNITS / 2f */\n")))
        assertQuiet("longer names that only start like the words", mathProblems(edit(drawSample, "val busy = ArtGeometry.busySign()\n", "val busy = ArtGeometry.busySign()\nval sqrtCount = 1\nval ART_FILLED = 2\n")))

        assertEquals("overlay: $art must hold no geometry number and no square root", emptyList<String>(), mathProblems(ModuleFiles.mainTexts().getValue(art)))
    }

    /** The number of times [part] occurs in [text]. */
    private fun countOf(text: String, part: String): Int = Regex.escape(part).toRegex().findAll(text).count()

    /** The inCell of the adapter as the real file has it: the frame, the save, the three canvas moves, the draw and the restore. */
    private val cellSample = "private inline fun inCell(canvas: Canvas, cell: TileRect, draw: (Float) -> Unit) {\n" +
        "val frame = ArtGeometry.frame(cell)\n" +
        "canvas.save()\n" +
        "canvas.translate(frame.centreX, frame.centreY)\n" +
        "canvas.scale(frame.scale, frame.scale)\n" +
        "canvas.translate(frame.originX, frame.originY)\n" +
        "draw(frame.scale)\n" +
        "canvas.restore()\n" +
        "}\n"

    /** The problems with the block body of inCell in [source]: each call once, the calls in order, the save once, and no arithmetic in the body. */
    private fun cellProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "inCell")
            ?: return listOf("TileArtDraw has no inCell function with a block body")
        val ordered = listOf(
            "val frame = ArtGeometry.frame(cell)",
            "canvas.translate(frame.centreX, frame.centreY)",
            "canvas.scale(frame.scale, frame.scale)",
            "canvas.translate(frame.originX, frame.originY)",
            "draw(frame.scale)",
            "canvas.restore()",
        )
        val missing = ordered.filter { countOf(body, it) != 1 }.map { "inCell does not hold $it exactly once" }
        val at = ordered.map { body.indexOf(it) }
        val outOfOrder = if (missing.isEmpty() && at.zipWithNext().any { (a, b) -> a >= b }) listOf("inCell holds its calls out of order") else emptyList<String>()
        val saved = if (countOf(body, "canvas.save()") == 1) emptyList<String>() else listOf("inCell does not hold canvas.save() exactly once")
        val arithmetic = if (Regex("[-+*/]").containsMatchIn(body)) listOf("inCell has arithmetic in its body, so it does more than pass the frame on") else emptyList<String>()
        return missing + outOfOrder + saved + arithmetic
    }

    /** The exact width line of drawGlow: each layer's width comes from that layer's own number. */
    private val glowLine = "paint.strokeWidth = ArtGeometry.glowWidthDp(layer) * density / scale"

    /** The drawGlow of the adapter as the real file has it, with the layer loop and its width line. */
    private val glowSample = "fun drawGlow(canvas: Canvas, cell: TileRect, paint: Paint, color: Int, pulseAlpha: Float, density: Float) {\n" +
        "inCell(canvas, cell) { scale ->\n" +
        "for (layer in 0 until ArtMetrics.GLOW_LAYERS) {\n" +
        "$glowLine\n" +
        "canvas.drawPath(silhouettePath, paint)\n" +
        "}\n" +
        "}\n" +
        "}\n"

    /** The problems with the width line of drawGlow in [source]: the exact line, present once in the body. */
    private fun glowProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawGlow")
            ?: return listOf("TileArtDraw has no drawGlow function with a block body")
        return listOfNotNull("drawGlow does not hold the line $glowLine exactly once".takeIf { countOf(body, glowLine) != 1 })
    }

    /** A failure means inCell does not move, scale and restore the canvas in the order the art box needs, or its body does arithmetic. */
    @Test
    fun `the cell frame is drawn through inCell in the order the geometry gives`() {
        assertQuiet("the sample", cellProblems(cellSample))
        assertFires("the scale multiplied", cellProblems(edit(cellSample, "canvas.scale(frame.scale, frame.scale)", "canvas.scale(frame.scale * 2f, frame.scale * 2f)")), "canvas.scale(frame.scale, frame.scale)")
        assertFires("the origin translate moved before the scale", cellProblems(edit(cellSample, "canvas.scale(frame.scale, frame.scale)\ncanvas.translate(frame.originX, frame.originY)\n", "canvas.translate(frame.originX, frame.originY)\ncanvas.scale(frame.scale, frame.scale)\n")), "out of order")
        assertFires("the restore dropped", cellProblems(edit(cellSample, "canvas.restore()\n", "")), "canvas.restore()")
        assertQuiet("a commented restore next to the real one", cellProblems(edit(cellSample, "canvas.restore()\n", "// canvas.restore()\ncanvas.restore()\n")))

        assertEquals("overlay: $art must draw the cell frame through inCell in the order the geometry gives", emptyList<String>(), cellProblems(ModuleFiles.mainTexts().getValue(art)))
    }

    /** A failure means drawGlow sets a layer's width from another layer's number, so the layers are not 4, 6 and 8 dp wide. */
    @Test
    fun `drawGlow sets each layer width from its own layer number`() {
        assertQuiet("the sample", glowProblems(glowSample))
        assertFires("the width of layer 0 for every layer", glowProblems(edit(glowSample, glowLine, "paint.strokeWidth = ArtGeometry.glowWidthDp(0) * density / scale")), "does not hold the line")
        assertFires("the width of the next layer", glowProblems(edit(glowSample, glowLine, "paint.strokeWidth = ArtGeometry.glowWidthDp(layer + 1) * density / scale")), "does not hold the line")
        assertQuiet("a commented width line next to the real one", glowProblems(edit(glowSample, glowLine, "// $glowLine\n$glowLine")))

        assertEquals("overlay: $art must set each glow layer width from its own layer number", emptyList<String>(), glowProblems(ModuleFiles.mainTexts().getValue(art)))
    }
}
