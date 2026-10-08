package dev.breaker.dictation.overlay

/**
 * Where everything sits inside the tile window, in pixels counted from the window's top-left corner.
 *
 * Every function takes [s], the side of the square tile in pixels (more than 0). The wide window is
 * three tiles across and one tile plus a strip of half a tile (`s / 2`) high. The strip is on top. Below
 * it, from left to right, are the cancel button, the microphone and the send button, each one tile
 * square. The level meter sits in the strip directly above the microphone, and a notice uses the
 * whole strip. Nothing here holds state or names a platform type.
 */
internal object TileLayout {

    private fun strip(s: Int): Int = s / 2

    /** The size of the window for [shape]: the square tile, or the wide window for a notice and for recording. */
    fun windowSize(shape: TileShape, s: Int): TileSize = when (shape) {
        TileShape.COLLAPSED -> TileSize(s, s)
        TileShape.NOTICE, TileShape.RECORDING -> TileSize(3 * s, s + strip(s))
    }

    /** The microphone's cell: the whole window when collapsed, the middle cell below the strip otherwise. */
    fun micCell(shape: TileShape, s: Int): TileRect = when (shape) {
        TileShape.COLLAPSED -> TileRect(0, 0, s, s)
        TileShape.NOTICE, TileShape.RECORDING -> TileRect(s, strip(s), 2 * s, strip(s) + s)
    }

    /** The cancel button's cell: left of the microphone, in the wide window. */
    fun cancelCell(s: Int): TileRect = TileRect(0, strip(s), s, strip(s) + s)

    /** The send button's cell: right of the microphone, in the wide window. */
    fun sendCell(s: Int): TileRect = TileRect(2 * s, strip(s), 3 * s, strip(s) + s)

    /** The level meter's area: in the strip, directly above the microphone's cell. */
    fun meterRect(s: Int): TileRect = TileRect(s, 0, 2 * s, strip(s))

    /** The area a notice is written in: the whole strip across the wide window. */
    fun noticeRect(s: Int): TileRect = TileRect(0, 0, 3 * s, strip(s))

    /**
     * The zone the pixel ([x], [y]) falls in, with both counted from the window's top-left corner.
     *
     * Collapsed: the microphone, or nothing. Notice: the microphone, the strip, or nothing; the
     * cancel and send cells are not drawn then, so they are nothing. Recording: the microphone, the
     * cancel button, the send button, the strip, or nothing.
     */
    fun zoneAt(x: Int, y: Int, shape: TileShape, s: Int): TileZone = when (shape) {
        TileShape.COLLAPSED -> if (micCell(shape, s).contains(x, y)) TileZone.MIC else TileZone.NONE
        TileShape.NOTICE -> when {
            micCell(shape, s).contains(x, y) -> TileZone.MIC
            noticeRect(s).contains(x, y) -> TileZone.STRIP
            else -> TileZone.NONE
        }
        TileShape.RECORDING -> when {
            micCell(shape, s).contains(x, y) -> TileZone.MIC
            cancelCell(s).contains(x, y) -> TileZone.CANCEL
            sendCell(s).contains(x, y) -> TileZone.SEND
            noticeRect(s).contains(x, y) -> TileZone.STRIP
            else -> TileZone.NONE
        }
    }

    /**
     * Splits [meter] into [count] lit-or-unlit cells, left to right, with full height.
     *
     * The cells touch with no gap and no overlap and their widths add up to the meter's width. Widths
     * differ by at most 1 pixel; when the meter is narrower than [count] some cells are empty. A
     * [count] of zero or less gives no cells.
     */
    fun segmentRects(count: Int, meter: TileRect): List<TileRect> {
        if (count <= 0) return emptyList()
        val width = meter.width
        return List(count) { i ->
            TileRect(
                left = meter.left + i * width / count,
                top = meter.top,
                right = meter.left + (i + 1) * width / count,
                bottom = meter.bottom,
            )
        }
    }

    /**
     * Where the window's top-left corner goes, on the screen, for a tile whose collapsed top-left
     * corner is [collapsed].
     *
     * Collapsed: [collapsed] itself. Otherwise the wide window is placed so its microphone cell
     * covers the collapsed tile (one tile left and one strip up), then moved the shortest way so the
     * whole window is inside [bounds]. A window larger than the bounds on an axis starts at the
     * bounds' left or top edge on that axis.
     */
    fun windowOrigin(shape: TileShape, collapsed: PixelPoint, s: Int, bounds: PixelBounds): PixelPoint {
        if (shape == TileShape.COLLAPSED) return collapsed
        val size = windowSize(shape, s)
        return PixelPoint(
            x = fit(collapsed.x - s, size.width, bounds.left, bounds.right),
            y = fit(collapsed.y - strip(s), size.height, bounds.top, bounds.bottom),
        )
    }

    /** [ideal] moved the shortest way so [extent] pixels from it lie between [low] (inside) and [high] (outside); [low] if they do not fit. */
    private fun fit(ideal: Int, extent: Int, low: Int, high: Int): Int {
        val last = high - extent
        return if (last < low) low else ideal.coerceIn(low, last)
    }
}

/**
 * The shape the tile window has for [state] and the app's [notice] (null when there is none).
 *
 * Recording always wins. Otherwise a notice gives the wide window and no notice gives the square tile.
 */
internal fun shapeOf(state: TileState, notice: String?): TileShape = when {
    state == TileState.RECORDING -> TileShape.RECORDING
    notice != null -> TileShape.NOTICE
    else -> TileShape.COLLAPSED
}
