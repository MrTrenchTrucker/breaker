package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.breaker.dictation.ui.render.controls.half
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.home.HomeGeometry

/*
 * The views of the node kinds that are neither boxes nor labels.
 */

/**
 * A small filled square in one colour, [side] pixels on each edge. It is not focusable and
 * carries no description; it only shows a status colour before a word.
 */
internal class DotView(context: Context, colour: Int, private val side: Int) : View(context) {
    init {
        setBackgroundColor(colour)
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(side, widthMeasureSpec), resolveSize(side, heightMeasureSpec))
    }
}

/**
 * A drawn back arrow: a chevron in one colour, at least the touch-target size. A tap reports
 * [intent] through [report]. It has no words of its own; the renderer sets its description.
 */
internal class BackArrowView(
    context: Context,
    private val intent: ScreenIntent,
    private val colour: Int,
    private val target: Int,
    private val report: (ScreenIntent) -> Unit,
) : View(context) {
    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setOnClickListener { report(intent) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(target, widthMeasureSpec), resolveSize(target, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centreX = half(width.toFloat())
        val centreY = half(height.toFloat())
        val arm = half(half(target.toFloat()))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = HomeGeometry.MENU_H_DP * density
        paint.color = colour
        val chevron = Path().apply {
            moveTo(centreX + arm, centreY - arm)
            lineTo(centreX - arm, centreY)
            lineTo(centreX + arm, centreY + arm)
        }
        canvas.drawPath(chevron, paint)
    }
}

/**
 * An empty block [side] pixels square. It takes no focus and is announced to no one: it only
 * holds a place so the words beside it stay centred.
 */
internal class TouchGapView(context: Context, private val side: Int) : View(context) {
    init {
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        minimumWidth = side
        minimumHeight = side
    }

    // A plain View takes the whole offered size when the offer is "at most", so in a row measured again
    // by a scroll view that fills its window it would grow to the window and push the page off screen.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(side, widthMeasureSpec), resolveSize(side, heightMeasureSpec))
    }
}
