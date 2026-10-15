package dev.breaker.dictation.ui.render

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.render.controls.SwitchView
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Theme

/**
 * A switch row: the label and its help text in a column, and the kit switch at the end. A tap on
 * the switch reports the row's intent.
 */
internal class SwitchRowView(
    context: Context,
    row: SwitchRow,
    private val theme: Theme,
    onIntent: (ScreenIntent) -> Unit,
) : LinearLayout(context) {

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(columnOf(row), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // the kit has no switch track constant of its own, so the track takes the kit values that match the art
        val toggle = SwitchView(
            context,
            row.label,
            ControlsFactors.TOUCH_MIN_DP,
            ControlsGeometry.BADGE_H_DP,
            theme,
        ).apply {
            on = row.on
            onToggled = { onIntent(row.intent) }
        }
        addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** The label above the help text. */
    private fun columnOf(row: SwitchRow): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(wordsOf(row.label, PaletteSlot.TEXT))
        addView(wordsOf(row.help, PaletteSlot.TEXT_MUTED))
    }

    /** Words in the body role, painted in [slot]. */
    private fun wordsOf(words: String, slot: PaletteSlot): View = TextView(context).apply {
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        typeface = Typeface.create(theme.type.bodyFamily, Typeface.NORMAL)
        setTextColor(theme.color(slot).argb)
        text = words
    }
}
