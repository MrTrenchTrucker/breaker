package dev.breaker.dictation.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws the microphone art into one cell of the tile. The paths are built once, from [TileArt], the first
 * time they are needed; the two recording buttons take theirs from [ControlArt]. Every colour comes from [faceColor], [slotColor], [ringDrawColor], [glowDrawColor]
 * or the look. Nothing here fills the cell: only the art is painted.
 *
 * The art box is centred in the cell and sized by [ArtGeometry.frame], which gives the scale and the
 * origin. The canvas is scaled for that, so a stroke width given in pixels is divided by the scale before
 * it is set; a width in art units (the busy circle) is not.
 */
internal class TileArtDraw {

    private val silhouettePath: Path by lazy {
        Path().also { path -> TileArt.silhouette.forEach { addShape(path, it) } }
    }

    private val paths: Map<ArtShape, Path> by lazy {
        TileArt.all.associateWith { shape -> Path().also { addShape(it, shape) } }
    }

    private val controlPaths: Map<ArtShape, Path> by lazy {
        listOf(ControlArt.cancel, ControlArt.send).associateWith { shape -> Path().also { addShape(it, shape) } }
    }

    /**
     * The glow: the silhouette stroked once per layer, the innermost first, each layer wider and fainter
     * than the one before. The width of each layer is in pixels: the ring width plus the layer's step, on
     * each side.
     */
    fun drawGlow(canvas: Canvas, cell: TileRect, paint: Paint, color: Int, pulseAlpha: Float, density: Float) {
        inCell(canvas, cell) { scale ->
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            for (layer in 0 until ArtMetrics.GLOW_LAYERS) {
                paint.strokeWidth = ArtGeometry.glowWidthDp(layer) * density / scale
                paint.color = glowDrawColor(color, pulseAlpha, glowAlpha(layer))
                canvas.drawPath(silhouettePath, paint)
            }
        }
    }

    /**
     * The outline band: the silhouette stroked with the paint as the caller set it. The caller sets the colour
     * and a width in pixels; the width is converted to art units here, so it is the same width on screen.
     */
    fun strokeOutline(canvas: Canvas, cell: TileRect, paint: Paint) {
        inCell(canvas, cell) { scale ->
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = paint.strokeWidth / scale
            canvas.drawPath(silhouettePath, paint)
        }
    }

    /**
     * The body in svg order, the six slots (each body, then its strip, lit left to right in RECORDING), the
     * ledge and the lettering, then the circle-and-slash when the state is MIC_BUSY.
     */
    fun drawArt(canvas: Canvas, cell: TileRect, paint: Paint, face: TileFace, pulseAlpha: Float) {
        val look = face.look
        inCell(canvas, cell) {
            paint.style = Paint.Style.FILL
            for (shape in TileArt.bodyShapes) fillShape(canvas, paint, shape, faceColor(shape.part, look))
            for ((index, slot) in TileArt.slots.withIndex()) {
                val lit = slotLit(face.state, index, face.litSegments)
                fillShape(canvas, paint, slot.body, slotColor(false, lit, look))
                fillShape(canvas, paint, slot.top, slotColor(true, lit, look))
            }
            for (shape in TileArt.ledgeShapes) fillShape(canvas, paint, shape, faceColor(shape.part, look))
            fillShape(canvas, paint, TileArt.letters, faceColor(ArtPart.LETTERS, look))
            if (face.state == TileState.MIC_BUSY) drawBusy(canvas, paint, look, pulseAlpha)
        }
    }

    /**
     * Draws one recording button: the fill of [shape] in its face colour, then its trim outline on the same
     * Path, inside the art frame of [cell]. Widths are in art units, so the outline is
     * [ArtMetrics.CONTROL_OUTLINE_UNITS] wide and centred on the edge, joined with a miter limited by
     * [ArtMetrics.CONTROL_MITER_LIMIT]. The paint's join, miter and cap are put back afterwards.
     */
    fun drawControl(canvas: Canvas, cell: TileRect, paint: Paint, shape: ArtShape, look: TileLook) {
        val join = paint.strokeJoin
        val miter = paint.strokeMiter
        val cap = paint.strokeCap
        val path = controlPaths.getValue(shape)
        inCell(canvas, cell) {
            paint.style = Paint.Style.FILL
            paint.color = faceColor(shape.part, look)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.MITER
            paint.strokeCap = Paint.Cap.BUTT
            paint.strokeMiter = ArtMetrics.CONTROL_MITER_LIMIT
            paint.strokeWidth = ArtMetrics.CONTROL_OUTLINE_UNITS
            paint.color = look.glyphOutline
            canvas.drawPath(path, paint)
        }
        paint.strokeJoin = join
        paint.strokeMiter = miter
        paint.strokeCap = cap
    }

    /**
     * The busy sign in art units, drawn off-centre on the approved point: the dark outline first, in
     * [look.busyOutline] (the danger role shaded by [ArtFactors.BUSY_OUTLINE_SHADE]) with the outline
     * stroke and no pulse, then the red ring and slash in [look.ring] with the ring stroke, pulsing with
     * [pulseAlpha]. No geometry of its own: every number comes from the busy sign.
     */
    private fun drawBusy(canvas: Canvas, paint: Paint, look: TileLook, pulseAlpha: Float) {
        val busy = ArtGeometry.busySign()
        paint.style = Paint.Style.STROKE
        paint.color = look.busyOutline
        paint.strokeWidth = busy.outlineStroke
        canvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)
        paint.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(busy.slashFromX, busy.slashFromY, busy.slashToX, busy.slashToY, paint)
        paint.color = ringDrawColor(look.ring, pulseAlpha)
        paint.strokeWidth = busy.stroke
        canvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)
        paint.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(busy.slashFromX, busy.slashFromY, busy.slashToX, busy.slashToY, paint)
        paint.strokeCap = Paint.Cap.ROUND
    }

    private fun fillShape(canvas: Canvas, paint: Paint, shape: ArtShape, color: Int) {
        paint.color = color
        canvas.drawPath(paths.getValue(shape), paint)
    }

    /** Saves the canvas, moves the art box to the cell (centred, scaled), runs [draw] with the scale, and restores. */
    private inline fun inCell(canvas: Canvas, cell: TileRect, draw: (Float) -> Unit) {
        val frame = ArtGeometry.frame(cell)
        canvas.save()
        canvas.translate(frame.centreX, frame.centreY)
        canvas.scale(frame.scale, frame.scale)
        canvas.translate(frame.originX, frame.originY)
        draw(frame.scale)
        canvas.restore()
    }

    /** Adds one shape to [path], command by command: move, line, cubic or close, with its numbers in order. */
    private fun addShape(path: Path, shape: ArtShape) {
        val xy = shape.xy
        var at = 0
        for (op in shape.ops) {
            when (op) {
                TileArt.OP_MOVE -> { path.moveTo(xy[at], xy[at + 1]); at += 2 }
                TileArt.OP_LINE -> { path.lineTo(xy[at], xy[at + 1]); at += 2 }
                TileArt.OP_CUBIC -> {
                    path.cubicTo(xy[at], xy[at + 1], xy[at + 2], xy[at + 3], xy[at + 4], xy[at + 5])
                    at += 6
                }
                TileArt.OP_CLOSE -> path.close()
            }
        }
    }
}
