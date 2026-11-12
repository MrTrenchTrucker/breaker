package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * One line of typed text on the auth screen: the base of the user name box and the password box.
 *
 * The fill and the outline are painted with the same kit calls as the settings text box. That is a
 * deliberate copy, because the kit's field takes no typing and keeps its paint private. The box
 * never saves its text into a saved state (rotation and a killed process lose it on purpose), asks
 * the autofill service to leave it and its children alone, and tells a screen reader its [label]
 * as a hint, since the caption drawn above it is a separate view.
 */
internal open class AuthFieldView(
    context: Context,
    protected val theme: Theme,
    label: String,
    kind: Int,
) : EditText(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        inputType = kind
        maxLines = 1
        background = null
        val side = (spaceDp(theme.metrics, Space.S) * density).toInt()
        setPadding(side, side, side, side)
        minHeight = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), density)
        setTextColor(theme.color(PaletteSlot.TEXT).argb)
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.hintText = label
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(density, density)
        val field = ControlsGeometry.textField(width / density, height / density)
        val corner = ControlsFactors.CORNER_DP
        paint.style = Paint.Style.FILL
        paint.color = ControlsPalette.partColor(ControlPart.FIELD, theme)
        canvas.drawRoundRect(0f, 0f, field.width, field.height, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ControlsGeometry.OUTLINE_DP
        paint.color = ControlsPalette.partColor(ControlPart.FIELD_OUTLINE, theme)
        canvas.drawRoundRect(0f, 0f, field.width, field.height, corner, corner, paint)
        canvas.restore()
        super.onDraw(canvas)
    }
}
