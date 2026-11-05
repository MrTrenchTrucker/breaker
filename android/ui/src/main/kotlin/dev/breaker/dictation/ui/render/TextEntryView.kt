package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * The value box as a real text input: one line of text that reports the typed text as a setting
 * under the node's key. The save moments are the Done key and the loss of focus; a loss of focus
 * is checked after the next layout, so a box that a redraw removed commits nothing.
 *
 * The fill and the outline are painted with the same kit calls as the kit's text field. That is a
 * deliberate copy, because the kit's field takes no typing and keeps its paint private. The view
 * holds no state of its own beyond the commit rule, and it never logs or prints the text.
 */
internal class TextEntryView(
    context: Context,
    private val theme: Theme,
    node: ValueField,
    entryKey: String,
    onIntent: (ScreenIntent) -> Unit,
) : EditText(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val startText: String = if (node.muted) "" else node.text
    private val rule = EntryCommitRule(startText) { typed ->
        onIntent(ScreenIntent.SetSetting(entryKey, typed))
    }

    init {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = null
        val side = (spaceDp(theme.metrics, Space.S) * density).toInt()
        setPadding(side, side, side, side)
        minHeight = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), density)
        setTextColor(theme.color(PaletteSlot.TEXT).argb)
        setHintTextColor(theme.color(PaletteSlot.TEXT_MUTED).argb)
        hint = if (node.muted) node.text else null
        setText(startText)
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                rule.finished(text.toString())
                true
            } else {
                false
            }
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) post { if (isAttachedToWindow) rule.finished(text.toString()) }
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

/**
 * The view for a value node. A node with no key stays the display-only field; a node with a key is
 * a real text input that reports its text as a setting under that key.
 */
internal fun fieldView(
    context: Context,
    node: ValueField,
    theme: Theme,
    onIntent: (ScreenIntent) -> Unit,
): View {
    val key = node.entryKey ?: return valueFieldView(context, node, theme)
    return TextEntryView(context, theme, node, key, onIntent)
}
