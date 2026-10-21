package dev.breaker.dictation.wiring

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowInsets
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.shared.tokens.TruckingTokens

/** Sets [view]'s margins to the system bars and the cutout, and its padding to the gutter, now and whenever its insets change. */
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
    val (clear, inner) = layoutFor(padding, gutter)
    val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
    if (lp != null) {
        lp.setMargins(clear.left, clear.top, clear.right, clear.bottom)
        view.layoutParams = lp
        view.setPadding(inner.left, inner.top, inner.right, inner.bottom)
    } else {
        view.setPadding(
            clear.left + inner.left,
            clear.top + inner.top,
            clear.right + inner.right,
            clear.bottom + inner.bottom,
        )
    }
}

/**
 * Paints [activity]'s window background from the palette for [mode].
 *
 * Painted when the activity is created; re-painted whenever [repaintOnThemeChange]'s callback fires.
 */
internal fun paintWindowBackground(
    activity: Activity,
    mode: ThemeMode,
) {
    val night = isNight(activity.resources.configuration.uiMode)
    activity.window.setBackgroundDrawable(
        ColorDrawable(windowColorFor(mode, night)),
    )
}

/**
 * Returns a callback that repaints [activity]'s window background from the palette. The theme mode
 * used to pick the colour is read from the store at fire time, never captured — so repeated calls keep
 * matching whatever is currently stored in [settings].
 */
internal fun repaintOnThemeChange(activity: Activity, settings: SettingsStore): () -> Unit =
    { paintWindowBackground(activity, settings.load().themeMode) }
