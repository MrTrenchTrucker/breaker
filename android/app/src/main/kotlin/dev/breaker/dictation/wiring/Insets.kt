package dev.breaker.dictation.wiring

import dev.breaker.shared.tokens.TruckingTokens
import kotlin.math.ceil

/** The system bar insets on each edge of the window. */
internal data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The padding to apply on each edge of a view. */
internal data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * The pixels of [dp] at [density], rounded up so a gutter is never below its dp.
 *
 * The gutter is the outer padding the host applies around every screen's content,
 * inside the margins that keep the system bars and the cutout clear.
 */
internal fun gutterPx(dp: Int, density: Float): Int =
    ceil(dp.toDouble() * density.toDouble()).toInt()

/**
 * The layout a view takes from [clear] and [gutter]: the bars and the cutout are
 * margins, the gutter is padding.
 *
 * A view that can carry margins gets them, so the system bars and the cutout stay
 * clear of the host's own background while the gutter keeps the content off the
 * screen edge. A view that cannot takes the whole amount as padding instead.
 */
internal fun layoutFor(clear: Padding, gutter: Int): Pair<Padding, Padding> =
    Pair(clear, Padding(gutter, gutter, gutter, gutter))

/**
 * The padding that keeps content clear of both the system bars and the display cutout.
 * Each side is the larger of the bar inset and the cutout inset for that side,
 * so a cutout that reaches past the bars is still cleared.
 */
internal fun paddingFor(bars: Insets, cutout: Insets): Padding = Padding(
    left = maxOf(bars.left, cutout.left),
    top = maxOf(bars.top, cutout.top),
    right = maxOf(bars.right, cutout.right),
    bottom = maxOf(bars.bottom, cutout.bottom),
)

/**
 * The window background colour for the current theme.
 *
 * [mode] is the user's theme preference; [systemIsNight] reports whether the
 * system is in night mode. When the preference is [ThemeMode.SYSTEM] the
 * system decides; otherwise the preference wins.
 */
internal fun windowColorFor(mode: dev.breaker.dictation.core.model.ThemeMode, systemIsNight: Boolean): Int =
    TruckingTokens.palette(
        if (mode == dev.breaker.dictation.core.model.ThemeMode.DARK ||
            (mode == dev.breaker.dictation.core.model.ThemeMode.SYSTEM && systemIsNight)
        ) dev.breaker.shared.tokens.ThemeMode.DARK else dev.breaker.shared.tokens.ThemeMode.LIGHT
    ).bg.argb

/**
 * Whether [uiMode] reports night mode.
 *
 * [uiMode] is the `Configuration.uiMode` field; the night bits are
 * `UI_MODE_NIGHT_MASK` (0x30) and the night value is `UI_MODE_NIGHT_YES` (0x20).
 */
internal fun isNight(uiMode: Int): Boolean =
    (uiMode and 0x30) == 0x20
