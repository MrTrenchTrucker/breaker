package dev.breaker.dictation.ui.render.controls

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import dev.breaker.dictation.ui.controls.DownloadBarMath
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * The model download bar as one Android view: a track with soft corners, a fill in the primary
 * colour, and slanted stripes that move left to right inside the fill.
 *
 * The view takes the megabyte numbers the app already has and draws only what [DownloadBarMath]
 * returns. It draws no words: the page draws the percent and the megabytes under the bar.
 *
 * While the view is attached and the phone's animations are on, one animator asks for a redraw;
 * each redraw reads the stripe offset from the time since the view was attached. Detaching cancels
 * the animator.
 *
 * What this class cannot prove: nothing here runs without a device, so how the stripes look at the
 * phone's density and how smoothly they move are not checked by any test.
 */
internal class DownloadBarView(
    context: Context,
    /** The theme the parts are painted in. */
    private var theme: Theme,
) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val stripes = Path()
    private var doneMb: Int = 0
    private var totalMb: Int = 0
    private var checking: Boolean = false
    private var attachedAt: Long = 0L
    private var animator: ValueAnimator? = null

    /** Stores the megabytes read from the download progress and ends the checking state. */
    fun setProgress(doneMb: Int, totalMb: Int) {
        this.doneMb = doneMb
        this.totalMb = totalMb
        checking = false
        invalidate()
    }

    /** Shows the full bar of the checking state. */
    fun setChecking() {
        checking = true
        invalidate()
    }

    /** Paints the parts in [theme] from the next draw on, so a bar kept on screen follows a theme change. */
    fun setTheme(theme: Theme) {
        this.theme = theme
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedAt = SystemClock.uptimeMillis()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val barHeight = (DownloadBarMath.BAR_HEIGHT_DP * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(barHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width / density
        val h = height / density
        if (!(w > 0f && h > 0f)) return
        val elapsed = SystemClock.uptimeMillis() - attachedAt
        val offset = DownloadBarMath.stripeOffsetDp(elapsed, ValueAnimator.areAnimatorsEnabled())
        val fillWidth = w * DownloadBarMath.fillFraction(doneMb, totalMb, checking)
        val edge = DownloadBarMath.OUTLINE_INSET_DP
        val corner = DownloadBarMath.CORNER_DP
        canvas.save()
        canvas.scale(density, density)
        paint.style = Paint.Style.FILL
        paint.color = theme.color(PaletteSlot.SURFACE).argb
        rect.set(edge, edge, w - edge, h - edge)
        canvas.drawRoundRect(rect, corner, corner, paint)
        if (fillWidth > 0f) {
            rect.set(0f, 0f, fillWidth, h)
            paint.color = theme.color(PaletteSlot.PRIMARY).argb
            canvas.drawRoundRect(rect, corner, corner, paint)
            clip.reset()
            clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            drawStripes(canvas, fillWidth, offset)
            canvas.restore()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = DownloadBarMath.OUTLINE_DP
        paint.color = theme.color(PaletteSlot.TEXT_MUTED).argb
        rect.set(edge, edge, w - edge, h - edge)
        canvas.drawRoundRect(rect, corner, corner, paint)
        canvas.restore()
    }

    private fun drawStripes(canvas: Canvas, fillWidth: Float, offset: Float) {
        val top = DownloadBarMath.STRIPE_INSET_DP
        val bottom = DownloadBarMath.STRIPE_BOTTOM_DP
        val slant = DownloadBarMath.slantDp(bottom - top)
        val stripeWidth = DownloadBarMath.STRIPE_WIDTH_DP
        stripes.reset()
        for (start in DownloadBarMath.stripeStarts(fillWidth, offset)) {
            stripes.moveTo(start + slant, top)
            stripes.lineTo(start + slant + stripeWidth, top)
            stripes.lineTo(start + stripeWidth, bottom)
            stripes.lineTo(start, bottom)
            stripes.close()
        }
        paint.style = Paint.Style.FILL
        paint.color = theme.color(PaletteSlot.PRIMARY_HOVER).argb
        canvas.drawPath(stripes, paint)
    }
}
