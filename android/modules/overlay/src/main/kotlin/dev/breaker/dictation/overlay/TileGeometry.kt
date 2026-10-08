package dev.breaker.dictation.overlay

/**
 * A rectangle in whole pixels. The left and top edges are inside; the right and bottom edges are
 * the first pixels outside, so a rectangle holds [width] by [height] pixels.
 */
internal data class TileRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    /** The horizontal extent in pixels. */
    val width: Int get() = right - left

    /** The vertical extent in pixels. */
    val height: Int get() = bottom - top

    /** True when the pixel at ([x], [y]) is inside: left <= x < right and top <= y < bottom. */
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * The shape of the tile window.
 *
 * [COLLAPSED] is the plain square tile. [NOTICE] is the wide window with a strip of text above the
 * microphone. [RECORDING] is the wide window with the cancel button, the level meter and the send
 * button.
 */
internal enum class TileShape { COLLAPSED, NOTICE, RECORDING }

/** The part of the tile window a touch lands on. [NONE] is any pixel that belongs to nothing. */
internal enum class TileZone { MIC, CANCEL, SEND, STRIP, NONE }

/** The width and height of the tile window in pixels. */
internal data class TileSize(val width: Int, val height: Int)
