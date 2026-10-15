package dev.breaker.dictation.overlay

/**
 * Where everything sits inside the tile window, in pixels counted from the window's top-left corner.
 *
 * Every function takes [s], the side of the square tile in pixels (more than 0). The notice window is
 * three tiles across and one tile plus a strip of half a tile (`s / 2`) high: the strip is on top and
 * holds the notice text, and the microphone's cell sits below it. The recording window is three tiles
 * across and one tile high with no strip: from left to right, the cancel button, the microphone and the
 * send button, each one tile square. The level meter is not laid out here; it is the six slots of the
 * microphone's grille. Nothing here holds state or names a platform type.
 */
internal object TileLayout {

    private fun strip(s: Int): Int = s / 2

    /** The size of the window for [shape]: the square tile, the wide window with its strip for a notice, or the wide window with no strip for recording. */
    fun windowSize(shape: TileShape, s: Int): TileSize = when (shape) {
        TileShape.COLLAPSED -> TileSize(s, s)
        TileShape.NOTICE -> TileSize(3 * s, s + strip(s))
        TileShape.RECORDING -> TileSize(3 * s, s)
    }

    /** The microphone's cell: the whole window when collapsed, the middle cell below the notice strip, or the middle cell of the recording window. */
    fun micCell(shape: TileShape, s: Int): TileRect = when (shape) {
        TileShape.COLLAPSED -> TileRect(0, 0, s, s)
        TileShape.NOTICE -> TileRect(s, strip(s), 2 * s, strip(s) + s)
        TileShape.RECORDING -> TileRect(s, 0, 2 * s, s)
    }

    /** The cancel button's cell: left of the microphone, in the recording window. */
    fun cancelCell(s: Int): TileRect = TileRect(0, 0, s, s)

    /** The send button's cell: right of the microphone, in the recording window. */
    fun sendCell(s: Int): TileRect = TileRect(2 * s, 0, 3 * s, s)

    /** The area a notice is written in: the whole strip across the wide window. */
    fun noticeRect(s: Int): TileRect = TileRect(0, 0, 3 * s, strip(s))

    /**
     * The zone the pixel ([x], [y]) falls in, with both counted from the window's top-left corner.
     *
     * Collapsed: the microphone, or nothing. Notice: the microphone, the strip, or nothing; the
     * cancel and send cells are not drawn then, so they are nothing. Recording: the microphone, the
     * cancel button, the send button, or nothing; the recording window has no strip.
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
            else -> TileZone.NONE
        }
    }

    /**
     * Where the window's top-left corner goes, on the screen, for a tile whose collapsed top-left
     * corner is [collapsed].
     *
     * Collapsed: [collapsed] itself. Notice: the wide window is placed so its microphone cell covers
     * the collapsed tile (one tile left and one strip up). Recording: one tile left, with the top edge
     * level with the collapsed tile, since there is no strip. Either wide window is then moved the
     * shortest way so the whole window is inside [bounds]. A window larger than the bounds on an axis
     * starts at the bounds' left or top edge on that axis.
     */
    fun windowOrigin(shape: TileShape, collapsed: PixelPoint, s: Int, bounds: PixelBounds): PixelPoint {
        if (shape == TileShape.COLLAPSED) return collapsed
        val size = windowSize(shape, s)
        val above = if (shape == TileShape.NOTICE) strip(s) else 0
        return PixelPoint(
            x = fit(collapsed.x - s, size.width, bounds.left, bounds.right),
            y = fit(collapsed.y - above, size.height, bounds.top, bounds.bottom),
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
 * The two finished outcomes, [TileState.SENT] and [TileState.SENT_LOCAL], take the same shape as
 * [TileState.FAILED].
 */
internal fun shapeOf(state: TileState, notice: String?): TileShape = when (state) {
    TileState.RECORDING -> TileShape.RECORDING
    TileState.IDLE, TileState.ARMED, TileState.SENDING, TileState.FAILED, TileState.SENT, TileState.SENT_LOCAL, TileState.MIC_BUSY ->
        if (notice != null) TileShape.NOTICE else TileShape.COLLAPSED
}
