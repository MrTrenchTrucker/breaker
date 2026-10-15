package dev.breaker.dictation.ui.render.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.render.touchTargetPx
import dev.breaker.dictation.ui.theme.Theme

/**
 * The on/off switch as one Android view: a track with its thumb — the primary
 * track and the on thumb while on, the surface track with the muted outline
 * and the muted thumb while off.
 *
 * The view draws only what [ControlsGeometry.switch] returns: the track and
 * the thumb are the art's own, sized by [trackW] and [trackH] from the caller,
 * and [ControlsPalette.partColor] paints them. The view itself draws no words;
 * the caption belongs to the row the switch sits in.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how the
 * thumb sits in the track at real density, and whether the track's corners land
 * on the art's radius on a given screen.
 */
internal class SwitchView(
    context: Context,
    /** The caption the row carries beside the switch, read by screen readers. */
    val label: String,
    /** The track's width in dp, the art's own. */
    private val trackW: Float,
    /** The track's height in dp, the art's own. */
    private val trackH: Float,
    /** The theme the parts are painted in. */
    private val theme: Theme,
) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Whether the switch is on. Set it from the touch that flips it. */
    var on: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        contentDescription = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)
        setMeasuredDimension(
            resolveSize(target, widthMeasureSpec),
            resolveSize(target, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        if (w > 0f && h > 0f) {
            val parts = ControlsGeometry.switch(on, trackW, trackH)
            val corner = ControlsFactors.CORNER_DP
            paint.style = Paint.Style.FILL
            paint.color = ControlsPalette.partColor(if (on) ControlPart.TRACK else ControlPart.TRACK_OFF, theme)
            canvas.drawRoundRect(
                parts.track.x,
                parts.track.y,
                parts.track.x + parts.track.width,
                parts.track.y + parts.track.height,
                corner,
                corner,
                paint,
            )
            if (!on) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ControlsGeometry.OUTLINE_DP
                paint.color = ControlsPalette.partColor(ControlPart.OUTLINE, theme)
                canvas.drawRoundRect(
                    parts.track.x,
                    parts.track.y,
                    parts.track.x + parts.track.width,
                    parts.track.y + parts.track.height,
                    corner,
                    corner,
                    paint,
                )
            }
            paint.style = Paint.Style.FILL
            paint.color = ControlsPalette.partColor(if (on) ControlPart.THUMB else ControlPart.THUMB_OFF, theme)
            canvas.drawRoundRect(
                parts.thumb.x,
                parts.thumb.y,
                parts.thumb.x + parts.thumb.width,
                parts.thumb.y + parts.thumb.height,
                corner,
                corner,
                paint,
            )
        }
        canvas.restore()
    }
}
