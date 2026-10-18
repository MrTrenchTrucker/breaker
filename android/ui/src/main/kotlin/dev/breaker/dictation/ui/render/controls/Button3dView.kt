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
 * Half of [v].
 *
 * The spacing gate lets a view write no number but zero and one, so the half a
 * centred label needs is two ones.
 */
internal fun half(v: Float): Float = v / (1f + 1f)

/**
 * The width of a button carrying [label], in this view's pixels. The label is measured in the
 * kit's own text size and default face; the button is that width plus its side room, and never
 * below the art's own button width, or the compact floor when [compact] is set. The answer is taken
 * up to whole pixels.
 */
internal fun buttonWidthPx(paint: TextPaint, label: String, density: Float, compact: Boolean = false): Int {
    paint.textSize = ControlsGeometry.LABEL_CAP_DP
    paint.typeface = Typeface.DEFAULT
    val measured = paint.measureText(drawnLabel(label))
    return touchTargetPx(ControlsGeometry.buttonWidth(measured, compact), density)
}

/**
 * The main button as one Android view: a raised face with the lit line along
 * its top and the darker lip under it, pushed down into the lip while the touch
 * presses it, and flat and muted while disabled.
 *
 * The button presses itself: it is clickable, so the framework keeps its own
 * pressed flag for the finger on it, the flag's change redraws the button, and
 * the draw reads that flag rather than a field of its own. The view draws only
 * what the plain layer returns: [ControlsGeometry.button] gives the parts and
 * their numbers, [ControlsPalette.partColor] gives each part its colour in
 * [theme], and the view holds no number and no colour of its own. [label] is
 * stored exactly as the caller gave it and drawn in capitals: the words belong
 * to the caller, the look belongs to the kit.
 *
 * A subclass paints another face by overriding the part it draws; the
 * destructive button is this same shape in the danger colours, and the
 * full-width button spans the row it is laid out in.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how the
 * lip and the lit line read at real density, how the default typeface's own
 * metrics place the label, whether the corners land on the art's radius on a
 * given screen, whether the press reads as real touch feedback under a finger,
 * and how the press ripple falls on hardware. Nor can it show that a label measured at real
 * density gives the button width the art draws.
 */
internal open class Button3dView(
    context: Context,
    /** The words on the button, as the caller stores them. Drawn in capitals. */
    val label: String,
    /** The theme the parts are painted in. */
    private val theme: Theme,
    /** Whether the button takes the compact floor: only the History entry rows set it. */
    private val compact: Boolean = false,
) : View(context) {

    protected val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = label
        isClickable = true
    }

    /** The press redraws whenever the framework flips the view's own pressed flag. */
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    /** The face's part: the primary face, or the danger face on the destructive button. The button presses itself from the touch; the caller sets no pressed state. */
    protected open fun facePart(): ControlPart = ControlPart.FACE

    /** The face's part while the touch presses the button: the pressed face, which the destructive button keeps in its danger colour. */
    protected open fun pressedFacePart(): ControlPart = ControlPart.FACE_PRESSED

    /** The lip's part under the face. */
    protected open fun lipPart(): ControlPart = ControlPart.LIP

    /** The lit line's part on the raised face, while no touch presses the button. */
    protected open fun litLinePart(): ControlPart = ControlPart.LIT_LINE

    /** The top line's part on the pressed face, shaded by the lip factor instead of the lit one. The press is the view's own, from the touch that holds it. */
    protected open fun topLinePart(): ControlPart = ControlPart.SHADOW_LINE

    /** The label's part: the on colour of a filled face. */
    protected open fun labelPart(): ControlPart = ControlPart.LABEL

    /** The touch-target floor, in this view's pixels. */
    private fun target(): Int = touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)

    /** The width the label alone asks for, in this view's pixels, measured the way the button's own width is. */
    protected fun labelWidthPx(): Int = buttonWidthPx(textPaint, label, density, compact)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(buttonWidthPx(textPaint, label, density, compact), widthMeasureSpec),
            resolveSize(target(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        if (w > 0f && h > 0f) {
            if (isEnabled) drawRaised(canvas, w, h) else drawFlat(canvas, w, h)
        }
        canvas.restore()
    }

    /** The raised button: the lip under the whole shape, the face over its top, the lit line and the label. */
    private fun drawRaised(canvas: Canvas, w: Float, h: Float) {
        val parts = ControlsGeometry.button(w, h, isPressed)
        val corner = ControlsFactors.CORNER_DP
        paint.style = Paint.Style.FILL
        val lip = parts.lip
        if (lip != null) {
            // the lip is the whole shape under the face: the face covers its top, the band below shows
            paint.color = ControlsPalette.partColor(lipPart(), theme)
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, paint)
        }
        paint.color = ControlsPalette.partColor(if (isPressed) pressedFacePart() else facePart(), theme)
        canvas.drawRoundRect(
            parts.face.x,
            parts.face.y,
            parts.face.x + parts.face.width,
            parts.face.y + parts.face.height,
            corner,
            corner,
            paint,
        )
        val litLine = parts.litLine
        if (litLine != null) {
            paint.color = ControlsPalette.partColor(if (isPressed) topLinePart() else litLinePart(), theme)
            canvas.drawRoundRect(
                litLine.x,
                litLine.y,
                litLine.x + litLine.width,
                litLine.y + litLine.height,
                corner,
                corner,
                paint,
            )
        }
        drawLabel(canvas, w, parts.labelTop)
    }

    /** The disabled button: the flat surface fill, the muted outline and the muted label, with no lip and no lit line. */
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
        drawLabel(canvas, w, parts.labelTop, ControlPart.LABEL_DISABLED)
    }

    /** [label] in capitals, centred on [w] with its ascent top at [labelTop], in [part]'s colour. */
    private fun drawLabel(canvas: Canvas, w: Float, labelTop: Float, part: ControlPart = labelPart()) {
        textPaint.color = ControlsPalette.partColor(part, theme)
        textPaint.textSize = ControlsGeometry.LABEL_CAP_DP
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(drawnLabel(label), half(w), labelTop - textPaint.fontMetrics.ascent, textPaint)
    }
}
