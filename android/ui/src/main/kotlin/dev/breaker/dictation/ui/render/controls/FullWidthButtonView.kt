package dev.breaker.dictation.ui.render.controls

import android.content.Context
import android.view.View
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.ControlsGeometry
import dev.breaker.dictation.ui.render.touchTargetPx
import dev.breaker.dictation.ui.theme.Theme

/**
 * The full-width button as one Android view: the main button's own look laid out across the whole
 * row it is given.
 *
 * It takes the parent's measured row as its own width when the parent measures one, so it spans
 * the row. When the parent measures no row, it takes the width its label asks for. Either way it
 * never stands below the touch floor: a button that fills a permission row is a touch target first.
 */
internal class FullWidthButtonView(
    context: Context,
    /** The words on the button, as the caller stores them. Drawn in capitals. */
    label: String,
    /** The theme the parts are painted in. */
    theme: Theme,
    /** Whether the button takes the compact floor, passed up to the main button. */
    compact: Boolean = false,
) : Button3dView(context, label, theme, compact) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)
        // the row's width when the parent measures one, and null when it measures none
        val row: Int? = when (View.MeasureSpec.getMode(widthMeasureSpec)) {
            View.MeasureSpec.AT_MOST, View.MeasureSpec.EXACTLY -> View.MeasureSpec.getSize(widthMeasureSpec)
            else -> null
        }
        setMeasuredDimension(
            ControlsGeometry.fullWidthPx(row, labelWidthPx(), target),
            resolveSize(target, heightMeasureSpec),
        )
    }
}
