package dev.breaker.dictation.wiring

import android.view.WindowInsets
import dev.breaker.shared.tokens.TruckingTokens

/** Pads [view] by the system bars, the display cutout and the gutter, now and whenever its insets change. */
internal fun applyInsets(view: android.view.View) {
    val rootInsets = view.rootWindowInsets
    if (rootInsets != null) {
        applyPadding(view, rootInsets)
    }
    view.setOnApplyWindowInsetsListener { v, insets ->
        applyPadding(v, insets)
        insets
    }
}

private fun applyPadding(view: android.view.View, insets: WindowInsets) {
    val bars = insets.getInsets(WindowInsets.Type.systemBars())
    val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
    val padding = paddingFor(
        dev.breaker.dictation.wiring.Insets(bars.left, bars.top, bars.right, bars.bottom),
        dev.breaker.dictation.wiring.Insets(cutout.left, cutout.top, cutout.right, cutout.bottom),
    )
    val gutter = gutterPx(TruckingTokens.metrics.gutterDp, view.resources.displayMetrics.density)
    view.setPadding(
        padding.left + gutter,
        padding.top + gutter,
        padding.right + gutter,
        padding.bottom + gutter,
    )
}
