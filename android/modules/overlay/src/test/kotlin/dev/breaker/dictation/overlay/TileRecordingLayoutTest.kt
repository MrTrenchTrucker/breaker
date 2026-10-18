package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording window: three touch zones wide and one tile high with no strip, its three zones side by side
 * along the top, the zones it answers, the place it goes on the screen, and the touch zones at the minimum
 * size. The notice window keeps its half tile strip and is checked here too.
 */
class TileRecordingLayoutTest {

    private val s = 100

    /** A failure means the recording window is not three touch zones wide and one tile high, or it has a strip. */
    @Test
    fun `the recording window is three zones wide and one tile high with no strip`() {
        assertEquals("overlay: recording window at 100 expected 225 by 100", TileSize(225, 100), TileLayout.windowSize(TileShape.RECORDING, s))
        assertEquals("overlay: recording window at 101 expected 228 by 101", TileSize(228, 101), TileLayout.windowSize(TileShape.RECORDING, 101))
    }

    /** A failure means the notice window loses its half tile strip, or the strip is not half the side with the fraction dropped. */
    @Test
    fun `the notice window keeps its half tile strip`() {
        assertEquals("overlay: notice window at 100 expected 3s by 1.5s", TileSize(300, 150), TileLayout.windowSize(TileShape.NOTICE, s))
        assertEquals("overlay: notice window at 101 expected 303 by 151", TileSize(303, 151), TileLayout.windowSize(TileShape.NOTICE, 101))
        assertEquals("overlay: notice strip at 101 expected 303 by 50", TileRect(0, 0, 303, 50), TileLayout.noticeRect(101))
    }

    /** A failure means the cancel, mic and send zones are not side by side along the top of the recording window, or the mic art cell is not where the layout says. */
    @Test
    fun `the recording cells sit side by side along the top`() {
        assertEquals("overlay: cancel zone at 100", TileRect(0, 0, 75, 100), TileLayout.cancelCell(s))
        assertEquals("overlay: mic zone at 100", TileRect(75, 0, 150, 100), TileLayout.micZone(TileShape.RECORDING, s))
        assertEquals("overlay: send zone at 100", TileRect(150, 0, 225, 100), TileLayout.sendCell(s))
        assertEquals("overlay: mic art cell at 100", TileRect(66, 0, 166, 100), TileLayout.micCell(TileShape.RECORDING, s))
    }

    /** A failure means the top of the microphone zone answers as the strip, or the recording window answers as a strip anywhere. */
    @Test
    fun `the top of the microphone cell is the microphone and the recording zone has no strip`() {
        assertEquals("overlay: the top of the mic zone expected MIC", TileZone.MIC, TileLayout.zoneAt(100, 0, TileShape.RECORDING, s))
        assertEquals("overlay: the top row of the cancel zone expected CANCEL", TileZone.CANCEL, TileLayout.zoneAt(30, 0, TileShape.RECORDING, s))
        for (side in 1..40) {
            val size = TileLayout.windowSize(TileShape.RECORDING, side)
            for (x in 0 until size.width) for (y in 0 until size.height) {
                assertTrue(
                    "overlay: at $side the pixel ($x,$y) of the recording window expected not to be the strip",
                    TileLayout.zoneAt(x, y, TileShape.RECORDING, side) != TileZone.STRIP,
                )
            }
        }
    }

    /** A failure means the recording window does not sit one mic cell left of the collapsed tile with its top edge level, so its microphone is not over the collapsed tile. */
    @Test
    fun `the recording window origin puts its microphone cell over the collapsed tile`() {
        val origin = TileLayout.windowOrigin(TileShape.RECORDING, PixelPoint(500, 500), s, PixelBounds(0, 0, 2000, 2000))
        assertEquals("overlay: recording origin expected (434,500)", PixelPoint(434, 500), origin)
        val mic = TileLayout.micCell(TileShape.RECORDING, s)
        assertEquals(
            "overlay: the microphone cell on the screen expected to be the collapsed tile at (500,500) to (600,600)",
            TileRect(500, 500, 600, 600),
            TileRect(origin.x + mic.left, origin.y + mic.top, origin.x + mic.right, origin.y + mic.bottom),
        )
    }

    /** A failure means a touch zone is not 48 dp wide and one tile high at the tile size, or the touch zone constant is not 48. */
    @Test
    fun `the touch zones are 48 dp wide and one tile high at the tile size`() {
        val side = TileMetrics.TILE_SIZE_DP
        assertTrue("overlay: the tile size $side dp expected at least 48 dp", side >= 48)
        assertEquals("overlay: the tile size expected 64 dp", 64, side)
        assertEquals("overlay: the touch zone expected 48 dp", 48, TileMetrics.TOUCH_ZONE_DP)
        assertTrue("overlay: the touch zone expected at least the 48 dp minimum", TileMetrics.TOUCH_ZONE_DP >= 48)
        val zones = listOf(TileLayout.cancelCell(side), TileLayout.micZone(TileShape.RECORDING, side), TileLayout.sendCell(side))
        zones.forEach { zone ->
            assertEquals("overlay: a touch zone width expected 48 at 64 dp", 48, zone.width)
            assertEquals("overlay: a touch zone height expected 64 at 64 dp", 64, zone.height)
        }
    }
}
