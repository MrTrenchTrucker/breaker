package dev.breaker.dictation.overlay

import kotlin.math.floor

/**
 * Where a finger went down and what shape the tile window had at that moment.
 *
 * The raw down point is on the screen. A tap is routed by the zone that point falls in, so the point is
 * kept as it came, in floats, and turned into window pixels only when the zone is asked for. It has no
 * window and no clock; the controller starts a gesture here on every touch down.
 */
internal class TileGesture {

    private var downX = 0f
    private var downY = 0f

    /** The shape of the window when the finger went down. Collapsed until a gesture starts. */
    var shape: TileShape = TileShape.COLLAPSED
        private set

    /** Remember a finger going down at the raw point ([x], [y]) on a window of [shape]. */
    fun begin(x: Float, y: Float, shape: TileShape) {
        downX = x
        downY = y
        this.shape = shape
    }

    /**
     * The zone the down point falls in, for a window whose top-left corner is at [origin] on the
     * screen and whose tile side is [s] pixels.
     *
     * A collapsed window is the microphone and nothing else, and the system only hands it touches
     * that landed inside it, so a collapsed tile answers the microphone for any point. A wider window
     * has parts: its point is moved into window pixels and taken down to the whole pixel it lies in,
     * so a point half a pixel left of the window is outside it.
     */
    fun zone(origin: PixelPoint, s: Int): TileZone {
        if (shape == TileShape.COLLAPSED) return TileZone.MIC
        return TileLayout.zoneAt(
            floor(downX - origin.x).toInt(),
            floor(downY - origin.y).toInt(),
            shape,
            s,
        )
    }
}
