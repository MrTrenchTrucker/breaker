package dev.breaker.dictation.ui.render.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.render.touchTargetPx
import dev.breaker.dictation.ui.theme.Theme

/**
 * The on/off switch as one Android view: a track with its thumb, the primary
 * track and the on thumb while on, the surface track with the muted outline and
 * the muted thumb while off.
 *
 * A tap flips the switch and calls [onToggled] once with the new value. A value
 * set from code changes the drawing and calls nothing.
 *
 * The view draws only what [ControlsGeometry.switch] returns: the track and
 * the thumb are the art's own, sized by [trackW] and [trackH] from the caller,
 * and [ControlsPalette.partColor] paints them. The view itself draws no words;
 * the caption belongs to the row the switch sits in.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how the
 * thumb sits in the track at real density, whether the track's corners land
 * on the art's radius on a given screen, and how a screen reader announces the
 * switch.
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

    /** Whether the switch is on. A tap flips it; setting it from code does not call [onToggled]. */
    var on: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Called once with the new value after a tap flips the switch. Not called when [on] is set from code. */
    var onToggled: ((Boolean) -> Unit)? = null

    init {
        contentDescription = label
        isClickable = true
    }

    override fun performClick(): Boolean {
        on = !on
        val handled = super.performClick()
        onToggled?.invoke(on)
        return handled
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Switch::class.java.name
        info.isCheckable = true
        info.isChecked = on
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
            val outline = parts.outline
            if (outline != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ControlsGeometry.OUTLINE_DP
                paint.color = ControlsPalette.partColor(ControlPart.OUTLINE, theme)
                canvas.drawRoundRect(
                    outline.x,
                    outline.y,
                    outline.x + outline.width,
                    outline.y + outline.height,
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
                ControlsGeometry.THUMB_CORNER_DP,
                ControlsGeometry.THUMB_CORNER_DP,
                paint,
            )
        }
        canvas.restore()
    }
}
