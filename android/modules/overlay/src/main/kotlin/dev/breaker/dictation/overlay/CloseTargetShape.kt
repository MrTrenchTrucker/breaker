package dev.breaker.dictation.overlay

import kotlin.math.abs

/**
 * The shape of the close target: a see-through dark box the size of a keyboard space bar.
 *
 * The art is drawn on a canvas of ART_WIDTH by ART_HEIGHT units. The box is the canvas minus
 * ART_MARGIN units on every side, and its corners are arcs of ART_CORNER units. The whole art is
 * scaled by one uniform factor that fits the target window. The drawn box is the drop area: a tile
 * centre inside the box closes the tile, and the four corners that the arcs cut off are outside.
 *
 * This object holds sizes and the hit test only. It has no Android types, no state and no window.
 */
internal object CloseTargetShape {
    const val ART_WIDTH: Float = 740f
    const val ART_HEIGHT: Float = 200f
    const val ART_MARGIN: Float = 4f
    const val ART_CORNER: Float = 26f
    const val EDGE_IDLE_ART: Float = 3f
    const val EDGE_ACTIVE_ART: Float = 5f
    const val WIDTH_FRACTION: Float = 0.47f
    const val HEIGHT_FRACTION: Float = 0.059f

    /** The target window width in pixels for [bounds]: the width fraction of the usable width, truncated. */
    fun windowWidthPx(bounds: PixelBounds): Int = (WIDTH_FRACTION * bounds.width).toInt()

    /** The target window height in pixels for [bounds]: the height fraction of the usable height, truncated. */
    fun windowHeightPx(bounds: PixelBounds): Int = (HEIGHT_FRACTION * bounds.height).toInt()

    /**
     * The uniform scale from art units to pixels for a window of the given sides: the smaller of the
     * two ratios. It is 0 when either side is not positive.
     */
    fun scale(windowWidthPx: Int, windowHeightPx: Int): Float {
        if (windowWidthPx <= 0 || windowHeightPx <= 0) {
            return 0f
        }
        return minOf(windowWidthPx / ART_WIDTH, windowHeightPx / ART_HEIGHT)
    }

    /** Half the drawn box width in pixels at [scale]. */
    fun halfWidthPx(scale: Float): Float = (ART_WIDTH / 2 - ART_MARGIN) * scale

    /** Half the drawn box height in pixels at [scale]. */
    fun halfHeightPx(scale: Float): Float = (ART_HEIGHT / 2 - ART_MARGIN) * scale

    /** The corner arc radius in pixels at [scale]. */
    fun cornerPx(scale: Float): Float = ART_CORNER * scale

    /**
     * Whether the point ([dxPx], [dyPx]), measured in pixels from the box centre, is inside the box.
     * A point exactly on the outline is inside. The result is false when [scale] is not positive.
     */
    fun contains(dxPx: Float, dyPx: Float, scale: Float): Boolean {
        if (scale <= 0f) {
            return false
        }
        val ax = abs(dxPx)
        val ay = abs(dyPx)
        val halfW = halfWidthPx(scale)
        val halfH = halfHeightPx(scale)
        if (ax > halfW || ay > halfH) {
            return false
        }
        val corner = cornerPx(scale)
        val straightX = halfW - corner
        val straightY = halfH - corner
        if (ax <= straightX || ay <= straightY) {
            return true
        }
        val arcX = ax - straightX
        val arcY = ay - straightY
        return arcX * arcX + arcY * arcY <= corner * corner
    }
}
