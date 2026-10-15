package dev.breaker.dictation.ui.render.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.render.touchTargetPx
import dev.breaker.dictation.ui.theme.Theme

/**
 * The permission steps as one Android view: [count] slanted marks, the ones the
 * current step has reached in the accent colour and the rest in the muted one,
 * each [markW] wide and [markH] tall and slanted by the geometry's own angle.
 *
 * The view draws only what [ControlsGeometry.stepMarks] returns: the marks and
 * their numbers, painted by [ControlsPalette.partColor] in [theme]. The marks
 * are display-only; the row that shows them owns the words.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how the
 * slant reads at real density, and whether the marks land on the art's own
 * spacing on a given screen.
 */
internal class StepMarksView(
    context: Context,
    /** How many marks to draw. */
    private val count: Int,
    /** The step the flow is on, one-based: the marks up to and including it are lit. */
    private val current: Int,
    /** Each mark's width in dp, the art's own. */
    private val markW: Float,
    /** Each mark's height in dp, the art's own. */
    private val markH: Float,
    /** The theme the parts are painted in. */
    private val theme: Theme,
) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val marks = ControlsGeometry.stepMarks(count, markW, markH)
        val last = marks.lastOrNull()
        val contentW = if (last == null) 0f else (last.x + last.width) * density
        val target = touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)
        setMeasuredDimension(
            resolveSize(maxOf(contentW.toInt(), target), widthMeasureSpec),
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
            val marks = ControlsGeometry.stepMarks(count, markW, markH)
            // two paths, one per colour: the marks the current step has reached are done, the rest are still to do
            val done = Path()
            val todo = Path()
            for ((index, mark) in marks.withIndex()) {
                val path = if (ControlsGeometry.isStepLit(index, current)) done else todo
                // a parallelogram: the top edge shifted right by the mark's slant
                path.moveTo(mark.x + mark.slant, mark.y)
                path.lineTo(mark.x + mark.slant + mark.width, mark.y)
                path.lineTo(mark.x + mark.width, mark.y + mark.height)
                path.lineTo(mark.x, mark.y + mark.height)
                path.close()
            }
            paint.style = Paint.Style.FILL
            paint.color = ControlsPalette.partColor(ControlPart.STEP_DONE, theme)
            canvas.drawPath(done, paint)
            paint.color = ControlsPalette.partColor(ControlPart.STEP_TODO, theme)
            canvas.drawPath(todo, paint)
        }
        canvas.restore()
    }
}
