package dev.breaker.dictation.overlay

/**
 * Where one recording button is drawn in the recording window: the scale of its art, the translation that
 * puts its art box in the window, and the window pixels its path bounding box lands on. The window's
 * top-left corner is the origin of every number.
 */
internal class ControlPlacement(
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The places of the recording window's parts, in window pixels counted from its top-left corner, for a tile
 * of side [s] pixels. The mic silhouette is measured from the art, and each button is placed from the bounding
 * box of its own path data. This is plain Kotlin with no platform names.
 */
internal object ControlLayout {

    /** The bounding box of a path, in its own art units. */
    private class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** The bounding box of the cross's path data, worked out once from its points. */
    private val crossBox: Box by lazy { boxOf(ControlArt.cancel) }

    /** The bounding box of the check's path data, worked out once from its points. */
    private val checkBox: Box by lazy { boxOf(ControlArt.send) }

    /** The left edge of the mic art cell in the recording window, in pixels. */
    private fun micLeft(s: Int): Float = TileLayout.micCell(TileShape.RECORDING, s).left.toFloat()

    /** The scale at which the mic art is drawn in its cell at side [s]. */
    private fun micScale(s: Int): Float = ArtGeometry.frame(TileLayout.micCell(TileShape.RECORDING, s)).scale

    /** The left edge of the mic silhouette in window pixels: the mic cell's left edge, half a tile, and the silhouette's offset from the art centre. */
    fun silhouetteLeft(s: Int): Float =
        micLeft(s) + s / 2f + (ArtMetrics.SILHOUETTE_LEFT - TileArt.BOX / 2f) * micScale(s)

    /** The right edge of the mic silhouette in window pixels: the mic cell's left edge, half a tile, and the silhouette's offset from the art centre. */
    fun silhouetteRight(s: Int): Float =
        micLeft(s) + s / 2f + (ArtMetrics.SILHOUETTE_RIGHT - TileArt.BOX / 2f) * micScale(s)

    /** The vertical centre line of the mic silhouette in window pixels: half a tile, and the silhouette's offset from the art centre. */
    fun silhouetteCentreY(s: Int): Float =
        s / 2f + (ArtMetrics.SILHOUETTE_CENTRE_Y - TileArt.BOX / 2f) * micScale(s)

    /** The gap in pixels between the nearest edge of a button and the mic outline: the design's gap in dp, scaled to the tile. */
    fun gapPx(s: Int): Float = ArtMetrics.CONTROL_GAP_DP * s / TileMetrics.TILE_SIZE_DP

    /** The width in pixels of each button: the control width fraction of the mic silhouette's full width. */
    private fun controlWidth(s: Int): Float = ArtMetrics.CONTROL_WIDTH_FRACTION * (silhouetteRight(s) - silhouetteLeft(s))

    /** The cancel cross: its right edge sits one gap left of the mic outline, and it is centred on the outline's centre line. */
    fun cancel(s: Int): ControlPlacement {
        val left = silhouetteLeft(s) - gapPx(s) - controlWidth(s)
        return placed(crossBox, s, left)
    }

    /** The send check: its left edge sits one gap right of the mic outline, and it is centred on the outline's centre line. */
    fun send(s: Int): ControlPlacement {
        val left = silhouetteRight(s) + gapPx(s)
        return placed(checkBox, s, left)
    }

    /**
     * The art box of a button at its scale, moved so its left edge is at [left] and its vertical middle is on the mic
     * outline's centre line. The returned edges are where its path bounding box lands in window pixels.
     */
    private fun placed(box: Box, s: Int, left: Float): ControlPlacement {
        val scale = controlWidth(s) / ArtMetrics.CONTROL_BOX_WIDTH
        val translateX = left - ArtMetrics.CONTROL_BOX_LEFT * scale
        val translateY = silhouetteCentreY(s) - ArtMetrics.CONTROL_BOX_CENTRE_Y * scale
        val height = (box.bottom - box.top) * scale
        val top = silhouetteCentreY(s) - height / 2f
        return ControlPlacement(
            scale = scale,
            translateX = translateX,
            translateY = translateY,
            left = left,
            top = top,
            right = left + controlWidth(s),
            bottom = top + height,
        )
    }

    /** The bounding box of [shape] in art units, from the x and y numbers of every point of its path. */
    private fun boxOf(shape: ArtShape): Box {
        val xs = shape.xy.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = shape.xy.filterIndexed { i, _ -> i % 2 == 1 }
        return Box(
            left = xs.reduce { a, b -> minOf(a, b) },
            top = ys.reduce { a, b -> minOf(a, b) },
            right = xs.reduce { a, b -> maxOf(a, b) },
            bottom = ys.reduce { a, b -> maxOf(a, b) },
        )
    }
}
