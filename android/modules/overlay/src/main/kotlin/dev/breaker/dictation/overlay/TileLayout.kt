package dev.breaker.dictation.overlay

import kotlin.math.floor

/**
 * Where everything sits inside the tile window, in pixels counted from the window's top-left corner.
 *
 * Every function takes [s], the side of the square tile in pixels (more than 0). The notice window is
 * three tiles across and one tile plus a strip of half a tile (`s / 2`) high: the strip is on top and
 * holds the notice text, and the microphone's cell sits below it. The recording window is one tile high
 * with no strip, and it is cut into three touch zones side by side: the cancel zone, the microphone zone
 * and the send zone. A zone is the touch size of [TileMetrics.TOUCH_ZONE_DP] dp at the tile's scale, to the
 * nearest whole pixel with halves going up, so the three zones tile the window exactly. The microphone's art
 * is drawn centred on its zone. The level meter is not laid out here; it is the six slots of the microphone's
 * grille. Nothing here holds state or names a platform type.
 */
internal object TileLayout {

    private fun strip(s: Int): Int = s / 2

    /** The width in pixels of one touch zone for side [s]: the zone size in dp at the tile's scale, halves going up. */
    private fun zone(s: Int): Int = floor(TileMetrics.TOUCH_ZONE_DP * s.toFloat() / TileMetrics.TILE_SIZE_DP + 0.5f).toInt()

    /**
     * The left edge of the recording window's microphone art cell, in pixels. The cell is one tile square. Its left
     * edge is placed so that the outline's centre line, which lies [ArtMetrics.SILHOUETTE_CENTRE_X] units into the art
     * box, falls on the centre of the middle zone. The distance from the cell's centre to that line is the offset from
     * the box centre, scaled by the art scale, and the result goes to the nearest whole pixel with halves going up.
     */
    private fun recordingArtLeft(s: Int): Int {
        val scale = ArtGeometry.frame(TileRect(0, 0, s, s)).scale
        val middleCentre = 1.5f * zone(s)
        val outlineOffset = s / 2f + (ArtMetrics.SILHOUETTE_CENTRE_X - TileArt.BOX / 2f) * scale
        return floor(middleCentre - outlineOffset + 0.5f).toInt()
    }

    /** The size of the window for [shape]: the square tile, the wide window with its strip for a notice, or the recording window, three touch zones wide and one tile high with no strip. */
    fun windowSize(shape: TileShape, s: Int): TileSize = when (shape) {
        TileShape.COLLAPSED -> TileSize(s, s)
        TileShape.NOTICE -> TileSize(3 * s, s + strip(s))
        TileShape.RECORDING -> TileSize(3 * zone(s), s)
    }

    /**
     * The microphone's art cell: the whole window when collapsed, the middle cell below the notice strip, or, for the
     * recording window, a tile-square cell whose left edge centres the outline on the middle touch zone.
     */
    fun micCell(shape: TileShape, s: Int): TileRect = when (shape) {
        TileShape.COLLAPSED -> TileRect(0, 0, s, s)
        TileShape.NOTICE -> TileRect(s, strip(s), 2 * s, strip(s) + s)
        TileShape.RECORDING -> TileRect(recordingArtLeft(s), 0, recordingArtLeft(s) + s, s)
    }

    /**
     * The mic's touch zone: the rectangle a touch on the microphone is read in. For the recording window it is the
     * middle touch zone, one zone wide and one tile high; for the collapsed tile and the notice it is the microphone cell.
     */
    fun micZone(shape: TileShape, s: Int): TileRect = when (shape) {
        TileShape.COLLAPSED -> micCell(shape, s)
        TileShape.NOTICE -> micCell(shape, s)
        TileShape.RECORDING -> TileRect(zone(s), 0, 2 * zone(s), s)
    }

    /** The cancel button's touch zone: the left zone of the recording window, one zone wide and one tile high. */
    fun cancelCell(s: Int): TileRect = TileRect(0, 0, zone(s), s)

    /** The send button's touch zone: the right zone of the recording window, one zone wide and one tile high. */
    fun sendCell(s: Int): TileRect = TileRect(2 * zone(s), 0, 3 * zone(s), s)

    /** The area a notice is written in: the whole strip across the wide window. */
    fun noticeRect(s: Int): TileRect = TileRect(0, 0, 3 * s, strip(s))

    /**
     * The zone the pixel ([x], [y]) falls in, with both counted from the window's top-left corner.
     *
     * Collapsed: the microphone, or nothing. Notice: the microphone, the strip, or nothing; the
     * cancel and send cells are not drawn then, so they are nothing. Recording: the microphone, the
     * cancel button, the send button, or nothing; the recording window has no strip. The microphone is
     * read in its touch zone for every shape.
     */
    fun zoneAt(x: Int, y: Int, shape: TileShape, s: Int): TileZone = when (shape) {
        TileShape.COLLAPSED -> if (micZone(shape, s).contains(x, y)) TileZone.MIC else TileZone.NONE
        TileShape.NOTICE -> when {
            micZone(shape, s).contains(x, y) -> TileZone.MIC
            noticeRect(s).contains(x, y) -> TileZone.STRIP
            else -> TileZone.NONE
        }
        TileShape.RECORDING -> when {
            micZone(shape, s).contains(x, y) -> TileZone.MIC
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
     * the collapsed tile (one tile left and one strip up). Recording: the window is moved left by the
     * left edge of its microphone art cell, so that the cell covers the collapsed tile, and its top edge
     * is level with the collapsed tile, since there is no strip. Either wide window is then moved the
     * shortest way so the whole window is inside [bounds]. A window larger than the bounds on an axis
     * starts at the bounds' left or top edge on that axis.
     */
    fun windowOrigin(shape: TileShape, collapsed: PixelPoint, s: Int, bounds: PixelBounds): PixelPoint {
        if (shape == TileShape.COLLAPSED) return collapsed
        val size = windowSize(shape, s)
        val above = if (shape == TileShape.NOTICE) strip(s) else 0
        val across = if (shape == TileShape.NOTICE) s else micCell(TileShape.RECORDING, s).left
        return PixelPoint(
            x = fit(collapsed.x - across, size.width, bounds.left, bounds.right),
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
