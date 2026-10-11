package dev.breaker.dictation.wiring

/** The system bar insets on each edge of the window. */
internal data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The padding to apply on each edge of a view. */
internal data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

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
