package dev.breaker.dictation.ui.render.controls

import android.text.TextPaint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import dev.breaker.dictation.ui.controls.BadgeKind
import dev.breaker.dictation.ui.controls.drawnLabel
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.render.touchTargetPx
import dev.breaker.dictation.ui.theme.Theme

/**
 * A status badge as one Android view: the state's fill, the label in the on
 * colour, and the art's own size, which [ControlsGeometry.badge] works out from
 * the label's length at the art's per-character width. The pill is centred in
 * the space the view is given, by [ControlsGeometry.badgePill].
 *
 * The badge is display-only: it draws and is read, and takes no touch of its
 * own. [label] is stored exactly as the caller gave it and drawn in capitals.
 *
 * **What this class cannot prove.** Nothing here runs without a device: how the
 * three fills read in sunlight, how the default typeface's own metrics place the
 * label, and whether a label measured at real density gives the pill the art draws.
 */
internal class BadgeView(
    context: Context,
    /** The badge's words, as the caller stores them. Drawn in capitals. */
    val label: String,
    /** Which of the three kinds this badge is. */
    private val kind: BadgeKind,
    /** The theme the parts are painted in. */
    private val theme: Theme,
) : View(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = label
    }

    /** The fill the kind takes, from the palette by kind. */
    private fun facePart(): ControlPart = ControlsPalette.badgeFacePart(kind)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val badge = ControlsGeometry.badge(label)
        setMeasuredDimension(
            resolveSize(touchTargetPx(badge.width, density), widthMeasureSpec),
            resolveSize(touchTargetPx(badge.height, density), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        if (w > 0f && h > 0f) {
            val pill = ControlsGeometry.badgePill(label, w, h)
            paint.style = Paint.Style.FILL
            paint.color = ControlsPalette.partColor(facePart(), theme)
            canvas.drawRoundRect(
                pill.x,
                pill.y,
                pill.x + pill.width,
                pill.y + pill.height,
                ControlsFactors.CORNER_DP,
                ControlsFactors.CORNER_DP,
                paint,
            )
            textPaint.color = ControlsPalette.partColor(ControlPart.LABEL, theme)
            textPaint.textSize = ControlsGeometry.BADGE_CAP_DP
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textAlign = Paint.Align.CENTER
            val labelTop = pill.y + half(pill.height - ControlsGeometry.BADGE_CAP_DP)
            canvas.drawText(
                drawnLabel(label),
                pill.x + half(pill.width),
                labelTop - textPaint.fontMetrics.ascent,
                textPaint,
            )
        }
        canvas.restore()
    }
}
