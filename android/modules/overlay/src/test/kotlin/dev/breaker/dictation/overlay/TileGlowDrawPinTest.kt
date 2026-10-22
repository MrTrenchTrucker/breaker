package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DRAW-level pins: what the halo's two branches scale and clip by, which surface the Home mic asks for, which
 * layers the halo loop draws, and where the halo is drawn. A JVM test cannot render a `Canvas` (there is no
 * Robolectric here, and `Canvas`/`Paint`/`Path` are stubs), so the draw cannot be observed; the draw is pinned by
 * naming the exact line the draw is written on, and failing by name when that line is missing.
 *
 * Each pin runs first on edited copies of the real source, so a change that must be reported is made on the real text
 * with [edit] proving the text it replaces is there: a sample that goes stale is a NAMED failure, never a silent
 * pass. Then each pin runs on the real file as it is. The mutant each pin names is one of the mutants under
 * `plans/mutants/` (G3, G4, G5, M7, M8) or the halo's own place, and each edited copy below is exactly that mutant's
 * edit.
 *
 * G3 and G4 are the two halves of the halo, so each is pinned as a PAIR in the same branch: a pin that only greps
 * for one line would not see the other half's text change underneath it.
 */
class TileGlowDrawPinTest {

    private val art = "TileArtDraw.kt"
    private val screen = "ScreenMic.kt"
    private val tile = "TileView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun real(name: String): String = ModuleFiles.mainTexts().getValue(name)

    /** The code of [text] with its leading indentation taken off every line, so a sample can be written without it. */
    private fun unindent(text: String): String = text.lines().joinToString("\n") { it.trimStart() }

    /** The number of times [part] occurs in [text]. */
    private fun countOf(text: String, part: String): Int = Regex.escape(part).toRegex().findAll(text).count()

    private val layerLoop = "for (layer in ArtGeometry.haloLayerCount() - 1 downTo 0) {"

    /** The body of the halo loop in [source], braces included, or null when there is none. */
    private fun layerLoopBodyOf(source: String): String? {
        val at = Regex.escape(layerLoop).toRegex().find(source)?.range?.first ?: return null
        val open = at + layerLoop.length - 1
        val close = SourceText.closeOf(source, open)
        return if (close < 0) null else source.substring(open, close + 1)
    }

    private val haloLayerColour = "paint.color = glowDrawColor(haloColor, pulseAlpha, ArtGeometry.haloLayerAlpha(layer))"

    /** The problems of the halo loop in [source]: the loop's head, its two branches as pairs, and one colour per layer. */
    private fun layerLoopProblems(source: String): List<String> {
        val loop = layerLoopBodyOf(source)
            ?: return listOf("the halo loop $layerLoop is missing")
        return listOfNotNull(
            "the halo loop does not walk every layer from the outermost down to 0".takeIf { !Regex(Regex.escape(layerLoop)).containsMatchIn(source) },
            "the halo loop does not name the layer's colour with $haloLayerColour".takeUnless { loop.contains(haloLayerColour) },
            "the halo loop does not pair canvas.scale(halo.sidewaysPx, halo.downwardPx) with canvas.clipRect(-1f, 0f, 1f, 1f)"
                .takeUnless { Regex("""canvas\.scale\(halo\.sidewaysPx,\s*halo\.downwardPx\)\s*canvas\.clipRect\(-1f,\s*0f,\s*1f,\s*1f\)""").containsMatchIn(loop) },
            "the halo loop does not pair canvas.scale(halo.sidewaysPx, halo.upwardPx) with canvas.clipRect(-1f, -1f, 1f, 0f)"
                .takeUnless { Regex("""canvas\.scale\(halo\.sidewaysPx,\s*halo\.upwardPx\)\s*canvas\.clipRect\(-1f,\s*-1f,\s*1f,\s*0f\)""").containsMatchIn(loop) },
        )
    }

    private val inCellHead = "inCell(canvas, cell) { scale ->"

    /** The 1-based line number of the character at [at] in [text]. */
    private fun lineOf(text: String, at: Int): Int = text.substring(0, at).count { it == '\n' } + 1

    /** The line range of the `inCell(canvas, cell) { scale ->` lambda in [source], as (first, last); null when there is none. */
    private fun inCellLinesOf(source: String): Pair<Int, Int>? {
        val code = SourceText.code(source)
        val at = Regex.escape(inCellHead).toRegex().find(code)?.range?.first ?: return null
        val brace = at + inCellHead.indexOf('{')
        val close = SourceText.closeOf(code, brace)
        if (close < 0) return null
        return lineOf(code, at) to lineOf(code, close)
    }

    /** The block body of `drawGlow`'s `when (spec.kind)`, braces included, or null when there is none. */
    private fun kindBlockOf(source: String): String? {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawGlow") ?: return null
        val at = body.indexOf("when (spec.kind)")
        if (at < 0) return null
        val open = body.indexOf('{', at)
        if (open < 0) return null
        val close = SourceText.closeOf(body, open)
        return if (close < 0) null else body.substring(open, close + 1)
    }

    private val homeHaloCall = "halo(spec, haloColor)"
    private val homeStrokeCall = "strokes(ArtGeometry.glowOuterHalfWidthDp())"
    private val tileBranch = "GlowKind.TILE -> strokes(ArtGeometry.glowOuterHalfWidthDp(spec))"

    /** The offset of the halo's call in [code]: the call, never the declaration of the local fun that draws it. */
    private fun haloCallAt(code: String): Int? =
        Regex("""(?<!fun )${Regex.escape(homeHaloCall)}""").find(code)?.range?.first

    /**
     * The problems of the halo's place in [source]: the halo is called once, with the caller's own [HaloReach], and
     * outside the `inCell(canvas, cell) { scale ->` lambda, so its pixel-space reaches are not scaled a second time
     * by the cell transform and its centre is not moved by the cell centre.
     */
    private fun haloPlaceProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val at = haloCallAt(code)
            ?: return listOf("HaloBeforeCellPin: the halo is not called as $homeHaloCall, so the halo does not take the caller's spec and the Home mic's halo is not drawn")
        val lines = inCellLinesOf(source)
            ?: return listOf("HaloBeforeCellPin: drawGlow has no $inCellHead lambda to place the halo against")
        val haloLine = lineOf(code, at)
        return listOfNotNull(
            "HaloBeforeCellPin: the halo call $homeHaloCall at line $haloLine is inside the inCell lambda of lines ${lines.first}-${lines.second}, so the halo is drawn in the cell transform"
                .takeIf { haloLine in lines.first..lines.second },
            "HaloBeforeCellPin: the halo call $homeHaloCall at line $haloLine is after the inCell lambda of lines ${lines.first}-${lines.second}, so the halo is drawn over the strokes"
                .takeIf { haloLine > lines.second },
        )
    }

    /** The Home mic's `when` arm, as the source has it: the named geometry's strokes, the halo being drawn before inCell. */
    private val homeBranch = "GlowKind.SCREEN -> $homeStrokeCall"

    /** The problems of the `when (spec.kind)` block in [source]: TILE takes the strokes alone, SCREEN takes the named geometry's strokes. */
    private fun kindBlockProblems(source: String): List<String> {
        val block = kindBlockOf(source)
            ?: return listOf("drawGlow has no when (spec.kind) block with a body")
        return listOfNotNull(
            "the TILE branch does not take $tileBranch".takeUnless { block.contains(tileBranch) },
            "the SCREEN branch does not take $homeStrokeCall".takeUnless { block.contains(homeStrokeCall) },
        )
    }

    /** The problems of the `drawGlow` calls in [source]: the Home mic asks for the halo, the tile asks for its own strokes. */
    private fun drawCallProblems(source: String): List<String> {
        val homeCall = "artDraw.drawGlow(canvas, mic, paint, look.ring, 1f, density, GlowSpec.SCREEN, look.litSegment)"
        val tileCall = "artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density, GlowSpec.TILE, look.ring)"
        return listOfNotNull(
            "the Home mic does not call drawGlow with $homeCall".takeUnless { source.contains(homeCall) },
            "the tile does not call drawGlow with $tileCall".takeUnless { source.contains(tileCall) },
        )
    }

    /**
     * A failure means one of the draw-level lines is not there: the halo's upward branch scaled by its own reach
     * and clipped to the half above the centre (G3, G4), the Home mic asking for the SCREEN halo (G5), the halo
     * loop walking every layer including the innermost (M7), or the halo drawn inside the cell transform
     * (HaloBeforeCellPin).
     */
    @Test
    fun `the draw-level pins name the halo's two branches, the Home mic's spec, the layer loop and the halo's place`() {
        val artText = real(art)
        val screenText = real(screen)
        val tileText = real(tile)

        // the halo's place, proven able to fire on the edit its own mutant patch makes
        assertQuiet("the real halo's place", haloPlaceProblems(artText))
        assertFires("M8: the halo taken from a hard-coded spec", haloPlaceProblems(edit(artText, "if (spec.kind == GlowKind.SCREEN) halo(spec, haloColor)", "if (spec.kind == GlowKind.SCREEN) halo(GlowSpec.SCREEN, haloColor)")), "the halo is not called as halo(spec, haloColor)")
        assertFires("HaloBeforeCellPin: the halo drawn inside the cell transform", haloPlaceProblems(edit(artText, "if (spec.kind == GlowKind.SCREEN) halo(spec, haloColor)\n        inCell(canvas, cell) { scale ->", "inCell(canvas, cell) { scale ->\n            if (spec.kind == GlowKind.SCREEN) halo(spec, haloColor)")), "is inside the inCell lambda of lines")
        assertFires("HaloBeforeCellPin: the halo dropped", haloPlaceProblems(edit(artText, "if (spec.kind == GlowKind.SCREEN) halo(spec, haloColor)\n", "")), "the halo is not called as halo(spec, haloColor)")
        assertFires("HaloBeforeCellPin: the halo drawn after the strokes", haloPlaceProblems(edit(edit(artText, "        if (spec.kind == GlowKind.SCREEN) halo(spec, haloColor)\n        inCell(canvas, cell) { scale ->", "        inCell(canvas, cell) { scale ->"), "        }\n    }\n\n    /**\n     * The outline band", "        }\n        if (spec.kind == GlowKind.SCREEN) halo(spec, haloColor)\n    }\n\n    /**\n     * The outline band")), "is after the inCell lambda of lines")

        // the pins, each proven able to fire on the edit its own mutant patch makes
        assertQuiet("the real halo loop", layerLoopProblems(artText))
        assertFires("G3: the upward branch scaled by the downward reach", layerLoopProblems(edit(artText, "canvas.scale(halo.sidewaysPx, halo.upwardPx)", "canvas.scale(halo.sidewaysPx, halo.downwardPx)")), "the halo loop does not pair canvas.scale(halo.sidewaysPx, halo.upwardPx) with canvas.clipRect(-1f, -1f, 1f, 0f)")
        assertFires("G4: the upward branch clipped to the half below the centre", layerLoopProblems(edit(artText, "canvas.clipRect(-1f, -1f, 1f, 0f)", "canvas.clipRect(-1f, -1f, 1f, 1f)")), "the halo loop does not pair canvas.scale(halo.sidewaysPx, halo.upwardPx) with canvas.clipRect(-1f, -1f, 1f, 0f)")
        assertFires("M7: the halo loop with its innermost layer dropped", layerLoopProblems(edit(artText, layerLoop, "for (layer in ArtGeometry.haloLayerCount() - 2 downTo 0) {")), "is missing")

        assertQuiet("the real when (spec.kind) block", kindBlockProblems(artText))
        assertFires("the Home mic's strokes taken from the spec", kindBlockProblems(edit(artText, homeBranch, "GlowKind.SCREEN -> strokes(ArtGeometry.glowOuterHalfWidthDp(spec))")), "the SCREEN branch does not take strokes(ArtGeometry.glowOuterHalfWidthDp())")

        assertQuiet("the real drawGlow calls", drawCallProblems(screenText + tileText))
        assertFires("G5: the Home mic asking for the tile's strokes", drawCallProblems(edit(screenText, "GlowSpec.SCREEN, look.litSegment", "GlowSpec.TILE, look.litSegment") + tileText), "the Home mic does not call drawGlow")

        // the near-miss controls: a word the mutants write, where the word the draw needs must not be mistaken for it
        assertFires("an upward branch scaled by its own reach, with no downward branch left to be confused with", layerLoopProblems(edit(artText, "canvas.scale(halo.sidewaysPx, halo.downwardPx)", "canvas.scale(halo.sidewaysPx, halo.upwardPx)")), "the halo loop does not pair canvas.scale(halo.sidewaysPx, halo.downwardPx) with canvas.clipRect(-1f, 0f, 1f, 1f)")
        assertQuiet("a copy whose only change is the spacing of the clip", layerLoopProblems(edit(artText, "canvas.clipRect(-1f, -1f, 1f, 0f)", "canvas.clipRect(-1f,-1f,1f,0f)")))
        // a source pin greps the literal loop head, so this control documents the pin's real LIMITATION: a copy
        // whose layer count is written as a number equal to the constant has no literal to grep, and the pin
        // correctly reports the loop as missing. It cannot see that the number is equal.
        assertFires("a copy whose layer count is written as a number equal to the constant", layerLoopProblems(edit(artText, "ArtGeometry.haloLayerCount() - 1 downTo 0", "15 downTo 0")), "is missing")

        // and on the real file: the four lines are there, exactly once each, and the two scales are the two reaches
        assertEquals("overlay: $art must hold the halo loop $layerLoop exactly once", 1, countOf(artText, layerLoop))
        assertEquals("overlay: $art must hold the upward scale exactly once", 1, countOf(artText, "canvas.scale(halo.sidewaysPx, halo.upwardPx)"))
        assertEquals("overlay: $art must hold the upward clip exactly once", 1, countOf(artText, "canvas.clipRect(-1f, -1f, 1f, 0f)"))
        assertEquals("overlay: $art must hold the downward scale exactly once", 1, countOf(artText, "canvas.scale(halo.sidewaysPx, halo.downwardPx)"))
        assertEquals("overlay: $art must hold the downward clip exactly once", 1, countOf(artText, "canvas.clipRect(-1f, 0f, 1f, 1f)"))
        assertEquals("overlay: $screen must ask for the Home mic's halo", 1, countOf(screenText, "artDraw.drawGlow(canvas, mic, paint, look.ring, 1f, density, GlowSpec.SCREEN, look.litSegment)"))
        assertEquals("overlay: $tile must ask for the tile's strokes", 1, countOf(tileText, "artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density, GlowSpec.TILE, look.ring)"))
        assertEquals("overlay: $art must hold the halo call $homeHaloCall exactly once", 1, countOf(artText, homeHaloCall))

        assertEquals("overlay: the two halo scales must be the two different reaches", 2, listOf("canvas.scale(halo.sidewaysPx, halo.downwardPx)", "canvas.scale(halo.sidewaysPx, halo.upwardPx)").count { artText.contains(it) })
        assertEquals("overlay: $art must hold the draw-level lines as the pins name them", emptyList<String>(), layerLoopProblems(artText) + kindBlockProblems(artText) + drawCallProblems(screenText + tileText) + haloPlaceProblems(artText))
    }
}
