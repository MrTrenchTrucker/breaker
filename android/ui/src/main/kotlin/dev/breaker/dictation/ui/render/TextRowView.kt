package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.drawnLabel
import dev.breaker.dictation.ui.render.controls.half
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.home.HomeGeometry
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * The text look of an action: capitals in the text colour, in the display face and size that a
 * label of the display role uses, with no background, as tall as the touch floor. A one pixel
 * hairline runs under it in the trim colour, from the drawer's divider start to its divider end.
 * The words start at the drawer's label inset. A disabled row draws its words in the muted colour.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how the capitals sit in the
 * row at real density, and how the hairline reads on the screen.
 */
internal class TextRowView(
    context: Context,
    /** The words on the row, as the caller stores them. Drawn in capitals. */
    val label: String,
    /** The theme the row is painted in. */
    private val theme: Theme,
) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = label
    }

    /** A change of the framework's state, such as the view being disabled, redraws the row. */
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = roleTextSizePx(context, TypeRole.DISPLAY) / density
        textPaint.typeface = typefaceOf(TypeRole.DISPLAY, theme)
        val words = (textPaint.measureText(drawnLabel(label)) * density).toInt()
        setMeasuredDimension(
            resolveSize(words, widthMeasureSpec),
            resolveSize(touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bottom = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = theme.color(PaletteSlot.TRIM).argb
        canvas.drawRect(
            HomeGeometry.DIVIDER_X0_DP * density,
            bottom - 1f,
            HomeGeometry.DIVIDER_X1_DP * density,
            bottom,
            paint,
        )
        val h = height / density
        canvas.save()
        canvas.scale(density, density)
        textPaint.color = theme.color(PaletteSlot.TEXT).argb
        if (!isEnabled) textPaint.color = theme.color(PaletteSlot.TEXT_MUTED).argb
        textPaint.textSize = roleTextSizePx(context, TypeRole.DISPLAY) / density
        textPaint.typeface = typefaceOf(TypeRole.DISPLAY, theme)
        textPaint.textAlign = Paint.Align.LEFT
        val baseline = half(h) - half(textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent)
        canvas.drawText(drawnLabel(label), HomeGeometry.ITEM_LABEL_X_DP, baseline, textPaint)
        canvas.restore()
    }
}
