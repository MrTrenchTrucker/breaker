package dev.breaker.dictation.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The close target as one Android view: a see-through dark box the size of a keyboard space bar, with a
 * side view of a long-nose truck pulling a lowboy that carries a strapped red X.
 *
 * The picture is drawn with one uniform scale, centred in the view. The drawn box is the drop area: the
 * four corners that its arcs cut off are outside it. The box fill is translucent, at the opacity of the
 * current highlight. The edge, the truck, the straps and the load are opaque. The paths are built when the
 * size is known, and nothing is allocated while drawing. Every colour comes from [face].
 *
 * **What cannot be proven without a device.** Nothing here runs on a phone: how the box and the truck look
 * at the real screen density, how thin the edge reads against the wallpaper, whether the truck reads as a
 * truck at this size, and how the vendor delivers touches to an overlay window.
 */
internal class CloseTargetView(
    context: Context,
    initialFace: CloseTargetFace,
) : View(context) {

    private val fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val edgePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val truckPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strapPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }
    private val loadPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var truckPath: Path = Path()
    private var strapsBehindPath: Path = Path()
    private var loadPath: Path = Path()
    private var strapsOverPath: Path = Path()

    /** The uniform scale from art units to pixels, and the offset of the art canvas in the view. */
    private var scale: Float = 0f
    private var originX: Float = 0f
    private var originY: Float = 0f

    /** The highlight state: whether the tile centre is inside the target. */
    private var highlight: CloseHighlight = CloseHighlight.OUTSIDE

    /** What the view draws now. */
    var face: CloseTargetFace = initialFace
        private set

    /** Update the highlight state and redraw. */
    fun setHighlight(highlight: CloseHighlight) {
        if (this.highlight == highlight) {
            return
        }
        this.highlight = highlight
        invalidate()
    }

    /** Draw [face] and redraw now. */
    fun applyFace(face: CloseTargetFace) {
        this.face = face
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scale = CloseTargetShape.scale(w, h)
        originX = (w - CloseTargetShape.ART_WIDTH * scale) / 2f
        originY = (h - CloseTargetShape.ART_HEIGHT * scale) / 2f
        buildPaths()
    }

    /** Builds the four paths of the picture for the current scale and offset. */
    private fun buildPaths() {
        truckPath = toPath(CloseTargetArt.TRUCK)
        strapsBehindPath = toPath(CloseTargetArt.STRAPS_BEHIND)
        loadPath = toPath(CloseTargetArt.LOAD)
        strapsOverPath = toPath(CloseTargetArt.STRAPS_OVER)
    }

    /** The Android path of [commands], with every point mapped from art units to pixels. */
    private fun toPath(commands: List<PathCmd>): Path {
        val path = Path()
        for (command in commands) {
            when (command) {
                is MoveTo -> {
                    path.moveTo(mapX(command.x), mapY(command.y))
                }
                is LineTo -> {
                    path.lineTo(mapX(command.x), mapY(command.y))
                }
                is CurveTo -> {
                    path.cubicTo(
                        mapX(command.x1),
                        mapY(command.y1),
                        mapX(command.x2),
                        mapY(command.y2),
                        mapX(command.x),
                        mapY(command.y),
                    )
                }
                ClosePath -> {
                    path.close()
                }
            }
        }
        return path
    }

    private fun mapX(x: Float): Float = originX + x * scale

    private fun mapY(y: Float): Float = originY + y * scale

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scale <= 0f) {
            return
        }
        val active = highlight == CloseHighlight.INSIDE
        val cx = width / 2f
        val cy = height / 2f
        val halfWidth = CloseTargetShape.halfWidthPx(scale)
        val halfHeight = CloseTargetShape.halfHeightPx(scale)
        val corner = CloseTargetShape.cornerPx(scale)
        val left = cx - halfWidth
        val top = cy - halfHeight
        val right = cx + halfWidth
        val bottom = cy + halfHeight

        val opacity = if (active) CloseTargetLook.FILL_OPACITY_ACTIVE else CloseTargetLook.FILL_OPACITY_IDLE
        fillPaint.color = face.fill
        fillPaint.alpha = CloseTargetLook.alphaOf(opacity)
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, fillPaint)

        edgePaint.color = if (active) face.edgeActive else face.edge
        edgePaint.strokeWidth = (if (active) CloseTargetShape.EDGE_ACTIVE_ART else CloseTargetShape.EDGE_IDLE_ART) * scale
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, edgePaint)

        truckPaint.color = face.truck
        canvas.drawPath(truckPath, truckPaint)

        strapPaint.color = face.truck
        strapPaint.strokeWidth = CloseTargetArt.STRAP_ART * scale
        canvas.drawPath(strapsBehindPath, strapPaint)

        loadPaint.color = if (active) face.loadActive else face.load
        canvas.drawPath(loadPath, loadPaint)

        canvas.drawPath(strapsOverPath, strapPaint)
    }
}
