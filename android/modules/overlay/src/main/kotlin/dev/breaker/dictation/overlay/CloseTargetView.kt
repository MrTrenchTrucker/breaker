package dev.breaker.dictation.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The close target as one Android view: a surface circle, a danger-colour X, and a trim ring.
 *
 * The art is a placeholder — a surface circle with a danger X and a thin trim ring. No text is
 * drawn. The target is not focusable and not touchable (the window flags handle that); the view
 * only draws and holds no state beyond the face and the highlight.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how it looks at real
 * density, how the ring thickness reads, whether the X is recognisable as a close glyph, and any
 * vendor difference in how overlay windows deliver touches.
 */
internal class CloseTargetView(
    context: Context,
    initialFace: CloseTargetFace,
) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    /** What the view draws now. */
    var face: CloseTargetFace = initialFace
        private set

    /** The highlight state: whether the tile centre is inside the target. */
    private var highlight: CloseHighlight = CloseHighlight.OUTSIDE

    /** Update the highlight state and redraw. */
    fun setHighlight(highlight: CloseHighlight) {
        if (this.highlight == highlight) return
        this.highlight = highlight
        invalidate()
    }

    /** Draw [face] and redraw now. */
    fun applyFace(face: CloseTargetFace) {
        this.face = face
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f

        // Surface circle
        paint.style = Paint.Style.FILL
        paint.color = face.surface
        canvas.drawCircle(cx, cy, radius, paint)

        // Ring (thicker when highlighted)
        val ringStroke = if (highlight == CloseHighlight.INSIDE) RING_DP_HIGHLIGHT * density else RING_DP * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringStroke
        paint.color = face.trim
        canvas.drawCircle(cx, cy, radius - ringStroke / 2f, paint)

        // Danger X
        val inset = radius * CONTROL_INSET
        val left = cx - inset
        val top = cy - inset
        val right = cx + inset
        val bottom = cy + inset
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = X_STROKE_DP * density
        paint.color = face.danger
        canvas.drawLine(left, top, right, bottom, paint)
        canvas.drawLine(right, top, left, bottom, paint)
    }

    private companion object {
        /** The ring's thickness in dp. */
        const val RING_DP = 2

        /** The ring's thickness when highlighted, in dp. */
        const val RING_DP_HIGHLIGHT = 4

        /** The X stroke's thickness in dp. */
        const val X_STROKE_DP = 3

        /** Space kept free around the X, as a fraction of the target's radius. */
        const val CONTROL_INSET = 0.28f
    }
}
