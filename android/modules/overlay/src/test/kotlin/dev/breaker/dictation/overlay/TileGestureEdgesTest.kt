package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which part of a wide window a finger went down on, read at the edges of the window.
 *
 * The window is the recording one of a tile 100 pixels square with its top-left corner at (406, 1090):
 * it is 300 by 150 pixels, the top 50 rows are the strip, the cancel button is x 406..506, the
 * microphone x 506..606 and the send button x 606..706, all in y 1140..1240.
 *
 * These tests ask the gesture helper directly. Through the controller a point just above the window
 * cannot be told from the strip, because the strip does nothing either, so only the zone itself shows
 * whether a point above the top edge counts as outside.
 */
class TileGestureEdgesTest {

    private val origin = PixelPoint(406, 1090)
    private val side = 100

    /** The zone of the window where the finger went down at ([x], [y]) on the screen. */
    private fun zoneOf(x: Float, y: Float, shape: TileShape = TileShape.RECORDING): TileZone {
        val gesture = TileGesture()
        gesture.begin(x, y, shape)
        return gesture.zone(origin, side)
    }

    /** If this fails, a point between 0 and 1 pixel above the window is read as the first row of it, in the wide shapes. */
    @Test
    fun `a point above the top edge is outside the window`() {
        listOf(TileShape.RECORDING, TileShape.NOTICE).forEach { shape ->
            listOf(456f, 556f, 656f).forEach { x ->
                assertEquals("overlay: half a pixel above the $shape window at x $x is outside it", TileZone.NONE, zoneOf(x, 1089.5f, shape))
                assertEquals("overlay: a hundredth of a pixel above the $shape window at x $x is outside it", TileZone.NONE, zoneOf(x, 1089.01f, shape))
            }
            assertEquals("overlay: a whole pixel above the $shape window is outside it", TileZone.NONE, zoneOf(556f, 1089f, shape))
            assertEquals("overlay: y 1090 is the first row of the $shape window", TileZone.STRIP, zoneOf(556f, 1090f, shape))
            assertEquals("overlay: y 1139.9 is still the strip of the $shape window", TileZone.STRIP, zoneOf(556f, 1139.9f, shape))
        }
        assertEquals("overlay: y 1140 is the first row of the buttons", TileZone.CANCEL, zoneOf(456f, 1140f))
    }

    /** If this fails, a point between 0 and 1 pixel left of the window is read as the first column of it. */
    @Test
    fun `a point left of the window is outside it`() {
        assertEquals("overlay: half a pixel left of the window is outside it", TileZone.NONE, zoneOf(405.5f, 1190f))
        assertEquals("overlay: x 406 is the first column of the cancel button", TileZone.CANCEL, zoneOf(406f, 1190f))
        assertEquals("overlay: x 505.9 is still the cancel button", TileZone.CANCEL, zoneOf(505.9f, 1190f))
        assertEquals("overlay: x 506 is the first column of the microphone", TileZone.MIC, zoneOf(506f, 1190f))
    }
}
