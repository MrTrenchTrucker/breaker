package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.drawnLabel
import dev.breaker.dictation.ui.render.controls.half
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * A small outlined pill that holds one word in capitals, the tag a history row carries.
 *
 * The outline and the word take the colour of [slot] in [theme]. The box is the badge box the kit
 * gives the drawn word, and the corner and the outline come from the kit constants.
 */
internal class TagPillView(
    context: Context,
    private val word: String,
    private val slot: PaletteSlot,
    private val theme: Theme,
) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val drawn: String = drawnLabel(word)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = word
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val box = ControlsGeometry.badge(drawn)
        setMeasuredDimension(
            resolveSize(touchTargetPx(box.width, density), widthMeasureSpec),
            resolveSize(touchTargetPx(box.height, density), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        if (w > 0f && h > 0f) {
            val colour = theme.color(slot).argb
            val corner = ControlsFactors.CORNER_DP
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ControlsGeometry.OUTLINE_DP
            paint.color = colour
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
            textPaint.color = colour
            textPaint.textSize = ControlsGeometry.BADGE_CAP_DP
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textAlign = Paint.Align.CENTER
            val baseline = half(h) - half(textPaint.ascent() + textPaint.descent())
            canvas.drawText(drawn, half(w), baseline, textPaint)
        }
        canvas.restore()
    }
}
