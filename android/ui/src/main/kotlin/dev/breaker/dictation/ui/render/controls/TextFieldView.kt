package dev.breaker.dictation.ui.render.controls

import android.text.TextPaint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.render.touchTargetPx
import dev.breaker.dictation.ui.theme.Theme

/**
 * The text field as one Android view: the surface fill with the accent outline,
 * and the field's own words inside.
 *
 * The view draws only what [ControlsGeometry.textField] returns: the field's
 * box and its numbers, painted by [ControlsPalette.partColor] in [theme]. The
 * words are [label], a parameter: the field holds no text of its own.
 *
 * The field is a drawn look only: it takes no typing and no touch of its own. A
 * screen that needs a real text box paints one with FIELD and FIELD_OUTLINE.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how the
 * accent outline reads against the surface at real density, and how the default
 * typeface's own metrics place the words.
 */
internal class TextFieldView(
    context: Context,
    /** The field's words, as the caller stores them. */
    val label: String,
    /** The theme the parts are painted in. */
    private val theme: Theme,
) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

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
            val field = ControlsGeometry.textField(w, h)
            val corner = ControlsFactors.CORNER_DP
            paint.style = Paint.Style.FILL
            paint.color = ControlsPalette.partColor(ControlPart.FIELD, theme)
            canvas.drawRoundRect(0f, 0f, field.width, field.height, corner, corner, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ControlsGeometry.OUTLINE_DP
            paint.color = ControlsPalette.partColor(ControlPart.FIELD_OUTLINE, theme)
            canvas.drawRoundRect(0f, 0f, field.width, field.height, corner, corner, paint)
            textPaint.color = ControlsPalette.partColor(ControlPart.LABEL_NEUTRAL, theme)
            textPaint.textSize = ControlsGeometry.LABEL_CAP_DP
            textPaint.typeface = Typeface.MONOSPACE
            textPaint.textAlign = Paint.Align.LEFT
            // the words stand one corner in from the field's edge, centred on its height
            val labelTop = half(field.height - ControlsGeometry.LABEL_CAP_DP)
            canvas.drawText(label, corner, labelTop - textPaint.fontMetrics.ascent, textPaint)
        }
        canvas.restore()
    }
}
