package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile's glow strokes take their width from [GlowSpec.TILE]'s three reaches, so those reaches are read by
 * the production path and not by tests alone: a test that pins them stays green while the tile's real drawing
 * changes, which is the dead-constant gap this closes.
 *
 * The spec is read at the tile's own 64 dp cell ([TileMetrics.TILE_SIZE_DP]) and at no other, so the tile's glow
 * is today's width at every cell size: the strokes are a fixed dp width, not one that rescales with the cell.
 *
 * No test in this module runs a Canvas (there is no Robolectric here), so the before/after comparison is made on
 * the calls' numbers and on the source's canvas calls: the widths the TILE path now computes are the widths it
 * computed before the change (4, 6 and 8 dp at the tile's 64 dp cell), and the source still holds one
 * stroke-width line and one silhouette draw call per layer, three layers in all.
 */
class TileGlowSpecFitTest {

    private val cell = 64f

    private val art = "TileArtDraw.kt"

    /** The real TileArtDraw.kt, read as text. */
    private fun artText(): String = ModuleFiles.mainTexts().getValue(art)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    /** The number of times [part] occurs in [text]. */
    private fun countOf(text: String, part: String): Int = Regex.escape(part).toRegex().findAll(text).count()

    /** A failure means a TILE reach no longer names today's outer edge, so the tile's strokes would move. */
    @Test
    fun `the tile's spec names today's outer glow half width`() {
        // the spec is read at the tile's own 64 dp cell, so the tile's glow is today's width at every cell size
        val tileCell = TileMetrics.TILE_SIZE_DP.toFloat()
        assertEquals("overlay: the tile's outer glow half width expected 4 dp", 4f, ArtGeometry.glowOuterHalfWidthDp(GlowSpec.TILE), 0.0001f)
        // each of the three reaches implies that same half width, so the least of them is well defined
        assertEquals("overlay: the sideways reach expected to imply 4 dp", 4f, ArtGeometry.glowOuterHalfWidthDp(GlowSpec.TILE.sideways, ArtMetrics.SILHOUETTE_HALF_WIDTH, tileCell), 0.0001f)
        assertEquals("overlay: the downward reach expected to imply 4 dp", 4f, ArtGeometry.glowOuterHalfWidthDp(GlowSpec.TILE.downward, ArtMetrics.SILHOUETTE_HALF_HEIGHT, tileCell), 0.0001f)
        assertEquals("overlay: the upward reach expected to imply 4 dp", 4f, ArtGeometry.glowOuterHalfWidthDp(GlowSpec.TILE.upward, ArtMetrics.SILHOUETTE_HALF_HEIGHT, tileCell), 0.0001f)
    }

    /** The before/after comparison: the widths the TILE path now computes are the widths it computed before the change. */
    @Test
    fun `the tile's three strokes are today's widths`() {
        val outer = ArtGeometry.glowOuterHalfWidthDp(GlowSpec.TILE)
        for (layer in 0 until ArtMetrics.GLOW_LAYERS) {
            assertEquals("overlay: layer $layer expected the width it had before the change", ArtGeometry.glowWidthDp(layer), ArtGeometry.glowWidthDp(layer, outer), 0.0001f)
        }
        assertEquals("overlay: the layer 0 stroke expected 4 dp", 4f, ArtGeometry.glowWidthDp(0, outer), 0.0001f)
        assertEquals("overlay: the layer 1 stroke expected 6 dp", 6f, ArtGeometry.glowWidthDp(1, outer), 0.0001f)
        assertEquals("overlay: the layer 2 stroke expected 8 dp", 8f, ArtGeometry.glowWidthDp(2, outer), 0.0001f)
    }

    private val tileBranchCall = "strokes(ArtGeometry.glowOuterHalfWidthDp(spec))"
    private val homeCall = "strokes(ArtGeometry.glowOuterHalfWidthDp())"
    private val widthLine = "paint.strokeWidth = ArtGeometry.glowWidthDp(layer, outerHalfWidthDp) * density / scale"
    private val drawCall = "canvas.drawPath(silhouettePath, paint)"
    private val layerLoop = "for (layer in 0 until ArtMetrics.GLOW_LAYERS) {"
    private val haloCall = "halo(spec, haloColor)"
    private val drawHead = "fun drawGlow("

    /**
     * The drawGlow of the adapter as the real file has it: the eight parameters, the local halo fun at drawGlow scope,
     * the halo call before inCell, the strokes fun with its loop inside inCell, and the two call sites of the when that
     * closes the inCell lambda.
     */
    private val drawSample = "fun drawGlow(\n" +
        "    canvas: Canvas,\n" +
        "    cell: TileRect,\n" +
        "    paint: Paint,\n" +
        "    color: Int,\n" +
        "    pulseAlpha: Float,\n" +
        "    density: Float,\n" +
        "    spec: HaloReach,\n" +
        "    haloColor: Int,\n" +
        ") {\n" +
        "    fun halo(spec: HaloReach, haloColor: Int) {\n" +
        "    }\n" +
        "    if (spec.kind == GlowKind.SCREEN) $haloCall\n" +
        "    inCell(canvas, cell) {\n" +
        "        scale ->\n" +
        "        fun strokes(outerHalfWidthDp: Float) {\n" +
        "            $layerLoop\n" +
        "            $widthLine\n" +
        "            $drawCall\n" +
        "            }\n" +
        "        }\n" +
        "        when (spec.kind) {\n" +
        "            GlowKind.TILE -> $tileBranchCall\n" +
        "            GlowKind.SCREEN -> {\n" +
        "                $homeCall\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "}\n"

    /** The body of the local `strokes` fun of drawGlow, braces included, or null when drawGlow has none. */
    private fun strokesBodyOf(source: String): String? {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawGlow") ?: return null
        return AdapterRules.blockBodyOf(body, "strokes")
    }

    /** The parameters of `fun drawGlow(` in [source], in the order written, or null when there is no such head. */
    private fun drawParamsOf(source: String): List<String>? {
        val code = SourceText.code(source)
        val head = Regex("\\bfun\\s+drawGlow\\s*\\(").find(code) ?: return null
        var depth = 0
        var at = head.range.last
        while (at < code.length) {
            if (code[at] == '(') depth++
            if (code[at] == ')' && --depth == 0) break
            at++
        }
        if (at >= code.length) return null
        return code.substring(head.range.last + 1, at).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The body of the lambda `inCell` hands over, braces included, or null when [code] has no inCell lambda. */
    private fun inCellBodyOf(code: String): String? {
        val call = code.indexOf("inCell(canvas, cell)")
        if (call < 0) return null
        val open = code.indexOf('{', call)
        if (open < 0) return null
        val close = SourceText.closeOf(code, open)
        return if (close < 0) null else code.substring(open + 1, close)
    }

    /**
     * The ways the drawGlow shape of [source] differs from the real file's: the parameter list, and the order of the
     * halo call, `inCell` and `when (spec.kind)`. Offsets decide the order, because a count cannot: the halo is one
     * occurrence wherever it sits, so the old sample passed every count while putting the halo inside the `when`.
     */
    private fun shapeProblems(source: String, real: String): List<String> {
        val problems = ArrayList<String>()
        val sampleParams = drawParamsOf(source)
            ?: return listOf("drawSample does not hold the real file's head $drawHead(")
        val realParamsFound = drawParamsOf(real)
            ?: return listOf("the real file does not hold the head $drawHead(")
        if (sampleParams != realParamsFound) {
            problems += "drawSample's parameters $sampleParams are not the real file's $realParamsFound"
        }
        val code = SourceText.code(source)
        val haloAt = code.indexOf(haloCall)
        val inCellAt = code.indexOf("inCell(canvas, cell)")
        val whenAt = code.indexOf("when (spec.kind)")
        val strokesAt = code.indexOf("fun strokes(")
        val inCellBody = inCellBodyOf(code)
        if (inCellBody == null) return problems + "drawSample has no inCell lambda with a block body"
        if (strokesAt < 0) problems += "drawSample holds no strokes fun, so the tile's branch has nothing to draw"
        if (haloAt < 0) {
            problems += "drawSample holds no $haloCall, so the halo's position cannot match the real file"
        } else {
            if (haloAt > inCellAt) problems += "drawSample calls $haloCall at $haloAt, after inCell at $inCellAt, but the real file calls the halo before inCell"
            if (haloAt > whenAt) problems += "drawSample calls $haloCall at $haloAt, after when (spec.kind) at $whenAt, but the real file calls the halo before the when"
        }
        return problems
    }

    /** The problems with the tile's branch in [source]: the two call sites, the one width line, and the one silhouette draw call per layer. */
    private fun branchProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawGlow")
            ?: return listOf("TileArtDraw has no drawGlow function with a block body")
        val strokes = AdapterRules.blockBodyOf(body, "strokes")
            ?: return listOf("drawGlow has no strokes function with a block body")
        val problems = ArrayList<String>()
        if (countOf(body, tileBranchCall) != 1) problems += "drawGlow does not hold the tile's call $tileBranchCall exactly once"
        if (countOf(body, homeCall) != 1) problems += "drawGlow does not hold the Home mic's call $homeCall exactly once"
        if (countOf(body, widthLine) != 1) problems += "drawGlow does not hold the width line $widthLine exactly once"
        if (countOf(body, haloCall) != 1) problems += "drawGlow does not hold the halo call $haloCall exactly once"
        if (countOf(strokes, drawCall) != 1) problems += "the tile's strokes do not hold $drawCall exactly once"
        if (countOf(strokes, layerLoop) != 1) problems += "the tile's strokes do not hold the layer loop $layerLoop exactly once"
        if (countOf(strokes, drawCall) * ArtMetrics.GLOW_LAYERS != 3) problems += "the tile's branch does not draw the silhouette three times"
        val otherDraws = listOf("canvas.drawCircle", "canvas.drawLine", "canvas.drawRect", "canvas.drawRoundRect")
            .filter { countOf(strokes, it) > 0 }
        if (otherDraws.isNotEmpty()) problems += "the tile's strokes draw something other than the silhouette: $otherDraws"
        return problems
    }

    /** A failure means the tile's branch does not take its outer half width from the spec's reaches, or draws anything but the three silhouette strokes. */
    @Test
    fun `the tile's branch draws three strokes and nothing else`() {
        assertQuiet("the sample", branchProblems(drawSample))
        assertFires("the tile's call dropped", branchProblems(edit(drawSample, tileBranchCall, "strokes()")), "the tile's call")
        assertFires("the Home mic's call dropped", branchProblems(edit(drawSample, homeCall, tileBranchCall)), "the Home mic's call")
        assertFires("the width line changed", branchProblems(edit(drawSample, widthLine, "paint.strokeWidth = ArtGeometry.glowWidthDp(layer) * density / scale")), "the width line")
        assertFires("a second silhouette draw call", branchProblems(edit(drawSample, drawCall, "$drawCall\n$drawCall")), "$drawCall exactly once")
        assertFires("the layer loop dropped", branchProblems(edit(drawSample, layerLoop, "for (layer in 0 until 1) {")), "the layer loop")
        assertFires("a circle drawn by the strokes", branchProblems(edit(drawSample, drawCall, "$drawCall\ncanvas.drawCircle(0f, 0f, 1f, paint)")), "something other than the silhouette")
        assertFires("the halo drawn by the tile's branch", branchProblems(edit(drawSample, "GlowKind.TILE -> $tileBranchCall", "GlowKind.TILE -> {\n$haloCall\n$tileBranchCall\n}")), "the halo call")
        assertQuiet("a commented width line next to the real one", branchProblems(edit(drawSample, widthLine, "// $widthLine\n$widthLine")))

        assertEquals("overlay: $art must draw the tile's three strokes and nothing else", emptyList<String>(), branchProblems(artText()))
    }

    /**
     * The fixture's own claim, tested: `drawSample` depicts the real file's `drawGlow`. A failure here means the sample
     * has drifted from the file it says it mirrors — a different parameter list, or the halo call moved inside `inCell`
     * or inside the `when`, which every count in [branchProblems] passes either way.
     */
    @Test
    fun `the sample is the real file's drawGlow shape`() {
        val real = artText()
        assertQuiet("the sample as the real file has it", shapeProblems(drawSample, real))
        assertFires("a six-parameter sample", shapeProblems(drawSample.replace("    spec: HaloReach,\n    haloColor: Int,\n", ""), real), "parameters")
        assertFires("the halo after inCell", shapeProblems(edit(edit(drawSample, "    if (spec.kind == GlowKind.SCREEN) $haloCall\n", ""), "    inCell(canvas, cell) {\n", "    inCell(canvas, cell) {\n        $haloCall\n"), real), "before inCell")
        assertFires("the halo after the when", shapeProblems(edit(edit(drawSample, "    if (spec.kind == GlowKind.SCREEN) $haloCall\n", ""), "        when (spec.kind) {\n", "        when (spec.kind) {\n            $haloCall\n"), real), "before the when")
        assertFires("the halo gone", shapeProblems(drawSample.replace("if (spec.kind == GlowKind.SCREEN) $haloCall", "// $haloCall"), real), "no $haloCall")
    }
}
