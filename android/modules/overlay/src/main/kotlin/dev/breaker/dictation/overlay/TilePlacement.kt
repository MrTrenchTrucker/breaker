package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.TilePosition
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The area of the screen the tile may use, in pixels: the screen minus the system bars and cutouts.
 *
 * The origin ([left], [top]) is usually not zero, because the bars take space
 * at the edges. [right] and [bottom] are exclusive edges, so [width] and
 * [height] are plain differences.
 */
internal data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "right edge $right is left of the left edge $left" }
        require(bottom >= top) { "bottom edge $bottom is above the top edge $top" }
    }

    /** The horizontal extent in pixels. */
    val width: Int get() = right - left

    /** The vertical extent in pixels. */
    val height: Int get() = bottom - top
}

/** A point on the screen in whole pixels: the top-left corner of the tile. */
internal data class PixelPoint(val x: Int, val y: Int)

/**
 * The maths between a saved tile position and pixels on this screen.
 *
 * A saved position is a [TilePosition] with `x` and `y` between 0 and 1, and it
 * means the fraction of the movable range, not of the whole screen. The movable
 * range on an axis is the usable bounds minus the tile size: the distance the
 * tile's top-left corner can travel while the whole tile stays on screen. The
 * origin is the top-left corner of the usable bounds.
 *
 * So 0 puts the tile hard against the top or left edge of the usable area, 1
 * puts it hard against the bottom or right edge, and both are fully on screen.
 * 0.5 on both axes is centred. Because the fraction is of the movable range, a
 * saved position fits any screen size or density.
 *
 * Pixels come from a fraction by taking the nearest whole pixel, with an exact
 * half going up. Where the movable range is zero on an axis (the tile is as
 * large as the area, or larger) the tile sits at the origin on that axis and
 * the fraction is 0. This object holds no state and has no Android types.
 */
internal object TilePlacement {

    /** The range the tile's left edge can move over, in pixels. Never negative. */
    fun movableWidth(bounds: PixelBounds, sizePx: Int): Int = max(0, bounds.width - sizePx)

    /** The range the tile's top edge can move over, in pixels. Never negative. */
    fun movableHeight(bounds: PixelBounds, sizePx: Int): Int = max(0, bounds.height - sizePx)

    /**
     * The top-left pixel for [position] on these [bounds] with a tile [sizePx] wide and high.
     *
     * Each axis is the origin plus the fraction of the movable range, taken to
     * the nearest whole pixel with an exact half going up.
     */
    fun toPixels(position: TilePosition, bounds: PixelBounds, sizePx: Int): PixelPoint =
        PixelPoint(
            x = bounds.left + (position.x * movableWidth(bounds, sizePx)).roundToInt(),
            y = bounds.top + (position.y * movableHeight(bounds, sizePx)).roundToInt(),
        )

    /**
     * The nearest pixel to ([x], [y]) at which the whole tile is inside the [bounds].
     *
     * On each axis the result lies from the origin up to the origin plus the
     * movable range, both ends included. A point already inside comes back unchanged.
     */
    fun clamp(x: Int, y: Int, bounds: PixelBounds, sizePx: Int): PixelPoint =
        PixelPoint(
            x = x.coerceIn(bounds.left, bounds.left + movableWidth(bounds, sizePx)),
            y = y.coerceIn(bounds.top, bounds.top + movableHeight(bounds, sizePx)),
        )

    /**
     * The saved position for a tile whose top-left corner is at ([x], [y]).
     *
     * Each axis is the distance from the origin divided by the movable range,
     * held between 0 and 1, so a point outside the range gives 0 or 1. An axis
     * with no movable range gives 0.
     */
    fun toPosition(x: Int, y: Int, bounds: PixelBounds, sizePx: Int): TilePosition =
        TilePosition(
            x = fraction(x - bounds.left, movableWidth(bounds, sizePx)),
            y = fraction(y - bounds.top, movableHeight(bounds, sizePx)),
        )

    /** [offset] over [movable] held between 0 and 1, or 0 when there is no range to move over. */
    private fun fraction(offset: Int, movable: Int): Float =
        if (movable == 0) 0f else (offset.toFloat() / movable).coerceIn(0f, 1f)
}
