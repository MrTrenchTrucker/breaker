package dev.breaker.dictation.ui.render

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import dev.breaker.dictation.ui.controls.scaledAlpha
import dev.breaker.dictation.ui.render.controls.half

/*
 * The fill of a box that has an outline or an edge bar.
 *
 * The drawable holds no colour and no size of its own: the renderer passes in the ARGB
 * values and the widths in pixels, so the numbers come from the theme and the Home geometry.
 */

/**
 * Draws a box's fill, then a stroke inside its bounds, then a bar down its left edge.
 * A null colour leaves that layer out. Every colour is painted at the drawable's alpha.
 */
internal class CardDrawable(
    private val fill: Int,
    private val outline: Int?,
    private val outlineWidth: Float,
    private val edge: Int?,
    private val edgeWidth: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The alpha the drawable paints at; a colour keeps its own alpha and is scaled by this one. */
    private var drawAlpha: Int = paint.alpha

    override fun draw(canvas: Canvas) {
        val area = bounds
        val left = area.left.toFloat()
        val top = area.top.toFloat()
        val right = area.right.toFloat()
        val bottom = area.bottom.toFloat()
        paint.style = Paint.Style.FILL
        paintColour(fill)
        canvas.drawRect(left, top, right, bottom, paint)
        outline?.let { colour ->
            val halfStroke = half(outlineWidth)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth
            paintColour(colour)
            canvas.drawRect(left + halfStroke, top + halfStroke, right - halfStroke, bottom - halfStroke, paint)
        }
        edge?.let { colour ->
            paint.style = Paint.Style.FILL
            paintColour(colour)
            canvas.drawRect(left, top, left + edgeWidth, bottom, paint)
        }
    }

    /** Paints with [colour], its alpha scaled by the drawable's alpha. */
    private fun paintColour(colour: Int) {
        paint.color = scaledAlpha(colour, drawAlpha)
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }
}
