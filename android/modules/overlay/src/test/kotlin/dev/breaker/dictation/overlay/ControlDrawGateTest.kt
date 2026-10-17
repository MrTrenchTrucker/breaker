package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawing of the recording buttons, read as text: drawControl fills the shape and then strokes the same Path with
 * the trim outline, the paint's join, miter and cap are put back afterwards, and the constants and face arms are the
 * approved values. Each rule runs first on edited copies of the real source (a copy that must be reported is made with
 * the edit helper, which fails by name when its text is missing), and then on the real file. No test runs the view.
 */
class ControlDrawGateTest {

    private val draw = "TileArtDraw.kt"
    private val rules = "TileArtRules.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun realDraw(): String = ModuleFiles.mainTexts().getValue(draw)

    private fun realRules(): String = ModuleFiles.mainTexts().getValue(rules)

    /** The problems of drawControl in the real file with [old] replaced by [new]. */
    private fun drawCopy(old: String, new: String): List<String> =
        ControlDrawRules.drawControlProblems(edit(realDraw(), old, new))

    /** The problems of the constants and the face arms in the real rules file with [old] replaced by [new]. */
    private fun constantCopy(old: String, new: String): List<String> =
        ControlDrawRules.controlConstantProblems(edit(realRules(), old, new))

    /**
     * A failure here means the outline does not meet its corners with the miter join, so a ROUND or BEVEL corner
     * replaces the sharp tip of the approved cross and check.
     */
    @Test
    fun `the outline joins its corners with the miter join`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires("a ROUND join on the outline", drawCopy("paint.strokeJoin = Paint.Join.MITER", "paint.strokeJoin = Paint.Join.ROUND"), "join")
        assertFires("a BEVEL join on the outline", drawCopy("paint.strokeJoin = Paint.Join.MITER", "paint.strokeJoin = Paint.Join.BEVEL"), "join")
        assertQuiet(
            "a commented join next to the real one",
            drawCopy("paint.strokeJoin = Paint.Join.MITER", "// paint.strokeJoin = Paint.Join.ROUND\n            paint.strokeJoin = Paint.Join.MITER"),
        )
        assertQuiet("the join on two lines", drawCopy("paint.strokeJoin = Paint.Join.MITER", "paint.strokeJoin =\n            Paint.Join.MITER"))
    }

    /**
     * A failure here means the outline does not end its strokes with the butt cap, so a ROUND or SQUARE end sticks out
     * past the tip edge of the approved shapes.
     */
    @Test
    fun `the outline caps its ends with the butt cap`() {
        val cap = "paint.strokeCap = Paint.Cap.BUTT\n            paint.strokeMiter"
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires("a ROUND cap on the outline", drawCopy(cap, "paint.strokeCap = Paint.Cap.ROUND\n            paint.strokeMiter"), "cap")
        assertFires("a SQUARE cap on the outline", drawCopy(cap, "paint.strokeCap = Paint.Cap.SQUARE\n            paint.strokeMiter"), "cap")
        assertQuiet(
            "a commented cap next to the real one",
            drawCopy(cap, "// paint.strokeCap = Paint.Cap.ROUND\n            paint.strokeCap = Paint.Cap.BUTT\n            paint.strokeMiter"),
        )
    }

    /**
     * A failure here means the outline's miter limit is not the control miter limit of ArtMetrics, so a corner is
     * cut or kept against a limit the approved svg does not use.
     */
    @Test
    fun `the outline miter limit is the control miter limit`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires("a literal miter limit of 4f", drawCopy("paint.strokeMiter = ArtMetrics.CONTROL_MITER_LIMIT", "paint.strokeMiter = 4f"), "miter")
        assertQuiet(
            "a commented miter next to the real one",
            drawCopy("paint.strokeMiter = ArtMetrics.CONTROL_MITER_LIMIT", "// paint.strokeMiter = 4f\n            paint.strokeMiter = ArtMetrics.CONTROL_MITER_LIMIT"),
        )
        assertQuiet(
            "the miter limit on two lines",
            drawCopy("paint.strokeMiter = ArtMetrics.CONTROL_MITER_LIMIT", "paint.strokeMiter =\n            ArtMetrics.CONTROL_MITER_LIMIT"),
        )
    }

    /**
     * A failure here means the outline width is not the 22 art units of ArtMetrics, so the trim outline is too thin or
     * too thick against the approved svg outline.
     */
    @Test
    fun `the outline width is the control outline width in art units`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires(
            "the width left at the paint width",
            drawCopy("paint.strokeWidth = ArtMetrics.CONTROL_OUTLINE_UNITS", "paint.strokeWidth = paint.strokeWidth"),
            "width",
        )
        assertFires("a literal width of 22f", drawCopy("paint.strokeWidth = ArtMetrics.CONTROL_OUTLINE_UNITS", "paint.strokeWidth = 22f"), "width")
        assertQuiet(
            "the width on two lines",
            drawCopy("paint.strokeWidth = ArtMetrics.CONTROL_OUTLINE_UNITS", "paint.strokeWidth =\n            ArtMetrics.CONTROL_OUTLINE_UNITS"),
        )
    }

    /**
     * A failure here means the outline is not drawn in the trim colour look.glyphOutline, so the outline takes the
     * fill's colour or another look field and the button loses its dark edge.
     */
    @Test
    fun `the outline is drawn in the glyph outline colour`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires(
            "the outline in the face colour",
            drawCopy("paint.color = look.glyphOutline", "paint.color = faceColor(shape.part, look)"),
            "outline colour",
        )
        assertFires("the outline in the glyph colour", drawCopy("paint.color = look.glyphOutline", "paint.color = look.glyph"), "outline colour")
        assertQuiet(
            "a commented outline colour next to the real one",
            drawCopy("paint.color = look.glyphOutline", "// paint.color = faceColor(shape.part, look)\n            paint.color = look.glyphOutline"),
        )
    }

    /**
     * A failure here means the fill is not drawn in the face colour of its part, so the cross or the check takes the
     * outline colour and the fill does not show the approved danger or primary colour.
     */
    @Test
    fun `the fill is drawn in the face colour of its part`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires(
            "the fill in the glyph outline colour",
            drawCopy("paint.color = faceColor(shape.part, look)", "paint.color = look.glyphOutline"),
            "fill colour",
        )
        assertQuiet(
            "a commented fill colour next to the real one",
            drawCopy("paint.color = faceColor(shape.part, look)", "// paint.color = look.glyphOutline\n            paint.color = faceColor(shape.part, look)"),
        )
    }

    /**
     * A failure here means the fill pass is not the first pass in the FILL style or the outline is not the second pass in
     * the STROKE style, or a pass is missing, so the outline paints before the fill or the button is not both filled and
     * outlined.
     */
    @Test
    fun `the fill pass is drawn first in the fill style and the outline second in the stroke style`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires(
            "the fill in the stroke style",
            drawCopy("paint.style = Paint.Style.FILL\n            paint.color = faceColor(shape.part, look)", "paint.style = Paint.Style.STROKE\n            paint.color = faceColor(shape.part, look)"),
            "FILL style",
        )
        assertFires(
            "the outline in the fill style",
            drawCopy("paint.style = Paint.Style.STROKE\n            paint.strokeJoin = Paint.Join.MITER", "paint.style = Paint.Style.FILL\n            paint.strokeJoin = Paint.Join.MITER"),
            "STROKE style",
        )
        val swapped = edit(
            edit(
                realDraw(),
                "paint.style = Paint.Style.FILL\n            paint.color = faceColor(shape.part, look)",
                "paint.style = Paint.Style.STROKE\n            paint.color = faceColor(shape.part, look)",
            ),
            "paint.style = Paint.Style.STROKE\n            paint.strokeJoin = Paint.Join.MITER",
            "paint.style = Paint.Style.FILL\n            paint.strokeJoin = Paint.Join.MITER",
        )
        assertFires("the two passes swapped", ControlDrawRules.drawControlProblems(swapped), "STROKE style")
        assertFires(
            "the outline pass removed",
            drawCopy("paint.color = look.glyphOutline\n            canvas.drawPath(path, paint)", "paint.color = look.glyphOutline\n            Unit"),
            "exactly two passes",
        )
        assertFires(
            "the fill pass removed",
            drawCopy("paint.color = faceColor(shape.part, look)\n            canvas.drawPath(path, paint)", "paint.color = faceColor(shape.part, look)\n            Unit"),
            "exactly two passes",
        )
        assertQuiet(
            "the fill style on two lines",
            drawCopy("paint.style = Paint.Style.FILL\n            paint.color", "paint.style =\n            Paint.Style.FILL\n            paint.color"),
        )
    }

    /**
     * A failure here means the fill and the outline are not both drawn with the one local Path, so the outline follows
     * another shape than the one that was filled.
     */
    @Test
    fun `the fill and the outline are drawn on one shared path`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires(
            "the outline drawn on another path",
            drawCopy(
                "paint.color = look.glyphOutline\n            canvas.drawPath(path, paint)",
                "paint.color = look.glyphOutline\n            canvas.drawPath(controlPaths.getValue(ControlArt.send), paint)",
            ),
            "same path",
        )
    }

    /**
     * A failure here means the join, miter limit or cap is not put back after both passes, so the next draw of the
     * tile inherits the control's join, miter or cap, or the join, miter or cap is not read before the first pass.
     */
    @Test
    fun `the join miter and cap are restored after both passes`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires("the join restore missing", drawCopy("paint.strokeJoin = join", "Unit"), "restore the paint join")
        assertFires("the miter restore missing", drawCopy("paint.strokeMiter = miter", "Unit"), "restore the paint miter limit")
        assertFires("the cap restore missing", drawCopy("paint.strokeCap = cap", "Unit"), "restore the paint cap")
        assertQuiet("the join restore on two lines", drawCopy("paint.strokeJoin = join", "paint.strokeJoin =\n        join"))
    }

    /**
     * A failure here means a control constant or a face arm is not the approved value: the outline width is not 22f, the
     * miter limit is not 10f, or the cross and the check are not the danger and primary colours in faceColor.
     */
    @Test
    fun `the control constants and the two face arms are pinned`() {
        assertQuiet("the real rules", ControlDrawRules.controlConstantProblems(realRules()))
        assertFires(
            "an outline width of 20f",
            constantCopy("CONTROL_OUTLINE_UNITS: Float = 22f", "CONTROL_OUTLINE_UNITS: Float = 20f"),
            "CONTROL_OUTLINE_UNITS",
        )
        assertFires(
            "a miter limit of 4f",
            constantCopy("CONTROL_MITER_LIMIT: Float = 10f", "CONTROL_MITER_LIMIT: Float = 4f"),
            "CONTROL_MITER_LIMIT",
        )
        assertFires(
            "the cross in the primary colour",
            constantCopy("ArtPart.CANCEL_CROSS -> look.danger", "ArtPart.CANCEL_CROSS -> look.primary"),
            "CANCEL_CROSS",
        )
        assertFires(
            "the check in the danger colour",
            constantCopy("ArtPart.SEND_CHECK -> look.primary", "ArtPart.SEND_CHECK -> look.danger"),
            "SEND_CHECK",
        )
        assertQuiet(
            "a commented constant next to the real one",
            constantCopy("const val CONTROL_OUTLINE_UNITS", "// const val CONTROL_OUTLINE_UNITS: Float = 20f\n    const val CONTROL_OUTLINE_UNITS"),
        )
    }

    /**
     * A failure here means the fill or the outline is drawn outside the art cell block, so the pass is not in the art
     * frame of the button cell and its widths are not in art units.
     */
    @Test
    fun `the whole draw sits inside the art cell block`() {
        assertQuiet("the real drawing", ControlDrawRules.drawControlProblems(realDraw()))
        assertFires(
            "the passes without the inPlacement block",
            drawCopy(
                "inPlacement(canvas, placement) {\n            paint.style = Paint.Style.FILL\n            paint.color = faceColor(shape.part, look)",
                "run {\n            paint.style = Paint.Style.FILL\n            paint.color = faceColor(shape.part, look)",
            ),
            "inside inPlacement",
        )
        assertQuiet(
            "the inPlacement call on two lines",
            drawCopy("inPlacement(canvas, placement) {\n            paint.style = Paint.Style.FILL", "inPlacement(canvas,\n            placement) {\n            paint.style = Paint.Style.FILL"),
        )
    }

    /** The problems of inPlacement in the real file with [old] replaced by [new]. */
    private fun placementCopy(old: String, new: String): List<String> =
        ControlDrawRules.placementProblems(edit(realDraw(), old, new))

    /**
     * A failure here means the buttons are drawn at the wrong size or place: the canvas is not translated to the
     * placement, the placement scale is not applied on both axes, the scale comes before the translate, the drawing
     * falls outside the scale and the restore, or the canvas is not restored last.
     */
    @Test
    fun `the buttons are moved and scaled to their placement`() {
        assertQuiet("the real placement", ControlDrawRules.placementProblems(realDraw()))
        assertFires("the save missing from the first call", placementCopy("canvas.save()\n        canvas.translate(placement.translateX, placement.translateY)", "val saved = 0\n        canvas.translate(placement.translateX, placement.translateY)"), "does not save")
        assertFires("the translate removed", placementCopy("canvas.translate(placement.translateX, placement.translateY)", "val moved = placement.translateX"), "does not translate")
        assertFires("the scale of 1f on both axes", placementCopy("canvas.scale(placement.scale, placement.scale)", "canvas.scale(1f, 1f)"), "both axes")
        assertFires("the scale on the first axis only", placementCopy("canvas.scale(placement.scale, placement.scale)", "canvas.scale(placement.scale, 1f)"), "both axes")
        assertFires("a commented correct scale next to a wrong one", placementCopy("canvas.scale(placement.scale, placement.scale)", "// canvas.scale(placement.scale, placement.scale)\n        canvas.scale(1f, 1f)"), "both axes")
        assertFires("the translate and the scale swapped", placementCopy("canvas.translate(placement.translateX, placement.translateY)\n        canvas.scale(placement.scale, placement.scale)", "canvas.scale(placement.scale, placement.scale)\n        canvas.translate(placement.translateX, placement.translateY)"), "after it translates")
        assertFires("the drawing moved after the restore", placementCopy("draw()\n        canvas.restore()", "canvas.restore()\n        draw()"), "between the scale")
        assertFires("a canvas call after the restore", placementCopy("draw()\n        canvas.restore()", "draw()\n        canvas.restore()\n        canvas.save()"), "restore the canvas last")
    }
}
