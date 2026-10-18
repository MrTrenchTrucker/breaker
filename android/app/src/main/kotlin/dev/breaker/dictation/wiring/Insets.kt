package dev.breaker.dictation.wiring

import kotlin.math.ceil

/** The system bar insets on each edge of the window. */
internal data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The padding to apply on each edge of a view. */
internal data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * The pixels of [dp] at [density], rounded up so a gutter is never below its dp.
 *
 * The gutter is the outer margin the host applies around every screen's content.
 * It is added to the system bar insets, so each side's total padding is the
 * inset plus the gutter.
 */
internal fun gutterPx(dp: Int, density: Float): Int =
    ceil(dp.toDouble() * density.toDouble()).toInt()

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
