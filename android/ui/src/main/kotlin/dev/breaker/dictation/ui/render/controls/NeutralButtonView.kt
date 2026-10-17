package dev.breaker.dictation.ui.render.controls

import android.text.TextPaint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import dev.breaker.dictation.ui.controls.drawnLabel
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.render.touchTargetPx
import dev.breaker.dictation.ui.theme.Theme

/**
 * The neutral button as one Android view: the surface fill, the muted outline
 * and the lip under it, for the buttons that cancel or back out.
 *
 * A disabled button draws the flat look instead: the surface fill and the muted outline, with no
 * lip, and the muted label, the same as the disabled main button.
 *
 * The view draws only what the plain layer returns: [ControlsGeometry.neutral] and
 * [ControlsGeometry.disabled] give the parts and their numbers, [ControlsPalette.partColor] gives
 * each part its colour in [theme], and the view holds no number and no colour of its own.
 * [label] is stored exactly as the caller gave it and drawn in capitals: the words belong to
 * the caller, the look belongs to the kit.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how the
 * muted outline reads against the surface at real density, and how the default
 * typeface's own metrics place the label.
 */
internal class NeutralButtonView(
    context: Context,
    /** The words on the button, as the caller stores them. Drawn in capitals. */
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

    /** A change of the framework's state, such as the view being disabled, redraws the button. */
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)
        setMeasuredDimension(
            resolveSize(buttonWidthPx(textPaint, label, density), widthMeasureSpec),
            resolveSize(target, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        if (w > 0f && h > 0f && isEnabled) {
            val parts = ControlsGeometry.neutral(w, h)
            val corner = ControlsFactors.CORNER_DP
            paint.style = Paint.Style.FILL
            // the lip under the face, in the neutral lip colour
            paint.color = ControlsPalette.partColor(ControlPart.LIP_NEUTRAL, theme)
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
            // the surface face, filled and outlined in the muted colour
            paint.color = ControlsPalette.partColor(ControlPart.FACE_SURFACE, theme)
            canvas.drawRoundRect(
                parts.face.x,
                parts.face.y,
                parts.face.x + parts.face.width,
                parts.face.y + parts.face.height,
                corner,
                corner,
                paint,
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ControlsGeometry.OUTLINE_DP
            paint.color = ControlsPalette.partColor(ControlPart.OUTLINE, theme)
            canvas.drawRoundRect(
                parts.face.x,
                parts.face.y,
                parts.face.x + parts.face.width,
                parts.face.y + parts.face.height,
                corner,
                corner,
                paint,
            )
            // the label in the text colour
            textPaint.color = ControlsPalette.partColor(ControlPart.LABEL_NEUTRAL, theme)
            textPaint.textSize = ControlsGeometry.LABEL_CAP_DP
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(drawnLabel(label), half(w), parts.labelTop - textPaint.fontMetrics.ascent, textPaint)
        } else if (w > 0f && h > 0f) {
            drawFlat(canvas, w, h)
        }
        canvas.restore()
    }

    /** The disabled button: the surface fill and the muted outline, with no lip, and the muted label. */
    private fun drawFlat(canvas: Canvas, w: Float, h: Float) {
        val parts = ControlsGeometry.disabled(w, h)
        val corner = ControlsFactors.CORNER_DP
        paint.style = Paint.Style.FILL
        paint.color = ControlsPalette.partColor(ControlPart.FACE_SURFACE, theme)
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ControlsGeometry.OUTLINE_DP
        paint.color = ControlsPalette.partColor(ControlPart.OUTLINE, theme)
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
        // the label in the muted colour, drawn the way the enabled label is drawn
        textPaint.color = ControlsPalette.partColor(ControlPart.LABEL_DISABLED, theme)
        textPaint.textSize = ControlsGeometry.LABEL_CAP_DP
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(drawnLabel(label), half(w), parts.labelTop - textPaint.fontMetrics.ascent, textPaint)
    }
}
