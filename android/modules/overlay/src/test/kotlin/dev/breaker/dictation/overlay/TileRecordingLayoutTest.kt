package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording window: three tiles wide and one tile high with no strip, its three cells side by side
 * along the top, the zones it answers, the place it goes on the screen, and the touch cells at the
 * minimum size. The notice window keeps its half tile strip and is checked here too.
 */
class TileRecordingLayoutTest {

    private val s = 100

    /** A failure means the recording window is not three tiles wide and one tile high, or it has a strip. */
    @Test
    fun `the recording window is three tiles wide and one tile high with no strip`() {
        assertEquals("overlay: recording window at 100 expected three tiles by one", TileSize(300, 100), TileLayout.windowSize(TileShape.RECORDING, s))
        assertEquals("overlay: recording window at 101 expected three tiles by one", TileSize(303, 101), TileLayout.windowSize(TileShape.RECORDING, 101))
    }

    /** A failure means the notice window loses its half tile strip, or the strip is not half the side with the fraction dropped. */
    @Test
    fun `the notice window keeps its half tile strip`() {
        assertEquals("overlay: notice window at 100 expected 3s by 1.5s", TileSize(300, 150), TileLayout.windowSize(TileShape.NOTICE, s))
        assertEquals("overlay: notice window at 101 expected 303 by 151", TileSize(303, 151), TileLayout.windowSize(TileShape.NOTICE, 101))
        assertEquals("overlay: notice strip at 101 expected 303 by 50", TileRect(0, 0, 303, 50), TileLayout.noticeRect(101))
    }

    /** A failure means the cancel, mic and send cells are not side by side along the top of the recording window. */
    @Test
    fun `the recording cells sit side by side along the top`() {
        assertEquals("overlay: cancel cell at 100", TileRect(0, 0, 100, 100), TileLayout.cancelCell(s))
        assertEquals("overlay: mic cell at 100", TileRect(100, 0, 200, 100), TileLayout.micCell(TileShape.RECORDING, s))
        assertEquals("overlay: send cell at 100", TileRect(200, 0, 300, 100), TileLayout.sendCell(s))
    }

    /** A failure means the top of the microphone cell answers as the strip, or the recording window answers as a strip anywhere. */
    @Test
    fun `the top of the microphone cell is the microphone and the recording zone has no strip`() {
        assertEquals("overlay: the top of the mic cell expected MIC", TileZone.MIC, TileLayout.zoneAt(150, 0, TileShape.RECORDING, s))
        assertEquals("overlay: the top row of the cancel cell expected CANCEL", TileZone.CANCEL, TileLayout.zoneAt(50, 0, TileShape.RECORDING, s))
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

    /** A failure means the recording window does not go one tile left of the collapsed tile with its top edge level, so its microphone cell is not over the collapsed tile. */
    @Test
    fun `the recording window origin puts its microphone cell over the collapsed tile`() {
        val origin = TileLayout.windowOrigin(TileShape.RECORDING, PixelPoint(500, 500), s, PixelBounds(0, 0, 2000, 2000))
        assertEquals("overlay: recording origin expected (400,500)", PixelPoint(400, 500), origin)
        val mic = TileLayout.micCell(TileShape.RECORDING, s)
        assertEquals(
            "overlay: the microphone cell on the screen expected to be the collapsed tile at (500,500) to (600,600)",
            TileRect(500, 500, 600, 600),
            TileRect(origin.x + mic.left, origin.y + mic.top, origin.x + mic.right, origin.y + mic.bottom),
        )
    }

    /** A failure means a touch cell is not one tile square at the tile size, or the tile size is below the 48 dp minimum touch target. */
    @Test
    fun `the touch cells are one tile square at the minimum size`() {
        val side = TileMetrics.TILE_SIZE_DP
        assertTrue("overlay: the tile size $side dp expected at least 48 dp", side >= 48)
        assertEquals("overlay: the tile size expected 56 dp", 56, side)
        val cells = listOf(TileLayout.micCell(TileShape.RECORDING, side), TileLayout.cancelCell(side), TileLayout.sendCell(side))
        cells.forEach { cell ->
            assertEquals("overlay: a touch cell width expected $side", side, cell.width)
            assertEquals("overlay: a touch cell height expected $side", side, cell.height)
        }
    }
}
