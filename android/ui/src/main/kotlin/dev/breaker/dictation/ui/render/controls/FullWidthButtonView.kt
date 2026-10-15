package dev.breaker.dictation.ui.render.controls

import android.content.Context
import android.view.View
import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.render.touchTargetPx
import dev.breaker.dictation.ui.theme.Theme

/**
 * The full-width button as one Android view: the main button's own look laid
 * out across the whole row it is given.
 *
 * It takes the parent's measured width as its own, so it spans the row, and it
 * stands at the touch floor when the parent gives less — a button that fills a
 * permission row is a touch target first.
 */
internal class FullWidthButtonView(
    context: Context,
    /** The words on the button, as the caller stores them. Drawn in capitals. */
    label: String,
    /** The theme the parts are painted in. */
    theme: Theme,
) : Button3dView(context, label, theme) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)
        // the row's whole width when the parent measures one, never below the touch floor
        val width = when (View.MeasureSpec.getMode(widthMeasureSpec)) {
            View.MeasureSpec.AT_MOST, View.MeasureSpec.EXACTLY ->
                maxOf(View.MeasureSpec.getSize(widthMeasureSpec), target)
            else -> target
        }
        setMeasuredDimension(width, resolveSize(target, heightMeasureSpec))
    }
}
