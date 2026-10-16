package dev.breaker.dictation.wiring

import android.view.WindowInsets

/** Pads [view] by the system bars and the display cutout, now and whenever its insets change. */
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
    view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
}
