package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which part of a wide window a finger went down on, read at the edges of the window.
 *
 * The recording window is 100 pixels high with its top-left corner at (406, 1140): it is 225 by 100
 * pixels, the cancel button is x 406..481, the microphone x 481..556 and the send button x 556..631,
 * all in y 1140..1240. The notice window has its top-left corner at (406, 1090): it is 300 by 150
 * pixels, its top 50 rows are the strip and its microphone is x 506..606, y 1140..1240.
 *
 * These tests ask the gesture helper directly. Through the controller a point just above the window
 * cannot be told from a point outside it, because both do nothing, so only the zone itself shows whether
 * a point above the top edge counts as outside.
 */
class TileGestureEdgesTest {

    private val side = 100

    private fun originOf(shape: TileShape): PixelPoint =
        if (shape == TileShape.RECORDING) PixelPoint(406, 1140) else PixelPoint(406, 1090)

    /** The zone of the window where the finger went down at ([x], [y]) on the screen. */
    private fun zoneOf(x: Float, y: Float, shape: TileShape = TileShape.RECORDING): TileZone {
        val gesture = TileGesture()
        gesture.begin(x, y, shape)
        return gesture.zone(originOf(shape), side)
    }

    /** If this fails, a point between 0 and 1 pixel above the window is read as the first row of it, in the wide shapes. */
    @Test
    fun `a point above the top edge is outside the window`() {
        listOf(TileShape.RECORDING, TileShape.NOTICE).forEach { shape ->
            val top = originOf(shape).y.toFloat()
            listOf(456f, 556f, 606f).forEach { x ->
                assertEquals("overlay: half a pixel above the $shape window at x $x is outside it", TileZone.NONE, zoneOf(x, top - 0.5f, shape))
                assertEquals("overlay: a hundredth of a pixel above the $shape window at x $x is outside it", TileZone.NONE, zoneOf(x, top - 0.99f, shape))
            }
            assertEquals("overlay: a whole pixel above the $shape window is outside it", TileZone.NONE, zoneOf(556f, top - 1f, shape))
        }
        assertEquals("overlay: y 1140 is the first row of the recording buttons", TileZone.CANCEL, zoneOf(456f, 1140f))
        assertEquals("overlay: y 1139.9 is outside the recording window", TileZone.NONE, zoneOf(556f, 1139.9f))
        assertEquals("overlay: y 1090 is the first row of the notice strip", TileZone.STRIP, zoneOf(556f, 1090f, TileShape.NOTICE))
        assertEquals("overlay: y 1139.9 is still the notice strip", TileZone.STRIP, zoneOf(556f, 1139.9f, TileShape.NOTICE))
    }

    /** If this fails, a point between 0 and 1 pixel left of the window is read as the first column of it. */
    @Test
    fun `a point left of the window is outside it`() {
        assertEquals("overlay: half a pixel left of the window is outside it", TileZone.NONE, zoneOf(405.5f, 1190f))
        assertEquals("overlay: x 406 is the first column of the cancel button", TileZone.CANCEL, zoneOf(406f, 1190f))
        assertEquals("overlay: x 480.9 is still the cancel button", TileZone.CANCEL, zoneOf(480.9f, 1190f))
        assertEquals("overlay: x 481 is the first column of the microphone", TileZone.MIC, zoneOf(481f, 1190f))
    }
}
