package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry of the tile window: the rectangle type, the window sizes, the cells, where the wide
 * window goes on the screen, and which shape the app's state asks for. Which zone a touch lands in is in
 * TileLayoutZonesTest; the recording window's own claims are in TileRecordingLayoutTest.
 */
class TileLayoutTest {

    private val s = 100

    private fun overlaps(a: TileRect, b: TileRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun inside(inner: TileRect, outer: TileRect): Boolean =
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom

    // ---- TileRect ----

    /** A failure means a rectangle's width or height is not the difference of its edges. */
    @Test
    fun `a rectangle has the width and height of its edges`() {
        val r = TileRect(10, 20, 15, 50)
        assertEquals("overlay: width of (10,20,15,50) expected 5", 5, r.width)
        assertEquals("overlay: height of (10,20,15,50) expected 30", 30, r.height)
    }

    /** A failure means the left or top edge is outside, or the right or bottom edge is inside, or an empty rectangle holds a pixel. */
    @Test
    fun `a rectangle holds its left and top edges and not its right and bottom edges`() {
        val r = TileRect(10, 20, 15, 50)
        listOf(10 to 20, 14 to 49, 10 to 49, 14 to 20, 12 to 30).forEach { (x, y) ->
            assertTrue("overlay: ($x,$y) expected inside (10,20,15,50)", r.contains(x, y))
        }
        listOf(15 to 30, 12 to 50, 15 to 50, 9 to 30, 12 to 19, 9 to 19, 100 to 100, -10 to -10).forEach { (x, y) ->
            assertFalse("overlay: ($x,$y) expected outside (10,20,15,50)", r.contains(x, y))
        }
        val empty = TileRect(5, 5, 5, 9)
        assertFalse("overlay: an empty rectangle expected to hold no pixel", empty.contains(5, 6))
        assertEquals("overlay: three values of the same rectangle expected equal", TileRect(1, 2, 3, 4), TileRect(1, 2, 3, 4))
    }

    // ---- sizes and cells ----

    /** A failure means a window size is wrong for its shape, on an even or an odd tile. */
    @Test
    fun `window sizes per shape`() {
        assertEquals("overlay: collapsed window at 100", TileSize(100, 100), TileLayout.windowSize(TileShape.COLLAPSED, 100))
        assertEquals("overlay: notice window at 100", TileSize(300, 150), TileLayout.windowSize(TileShape.NOTICE, 100))
        assertEquals("overlay: recording window at 100 (no strip)", TileSize(300, 100), TileLayout.windowSize(TileShape.RECORDING, 100))
        assertEquals("overlay: collapsed window at 101", TileSize(101, 101), TileLayout.windowSize(TileShape.COLLAPSED, 101))
        assertEquals("overlay: notice window at 101 (strip is 50)", TileSize(303, 151), TileLayout.windowSize(TileShape.NOTICE, 101))
        assertEquals("overlay: recording window at 101 (no strip)", TileSize(303, 101), TileLayout.windowSize(TileShape.RECORDING, 101))
        assertEquals("overlay: recording window at 1 (no strip)", TileSize(3, 1), TileLayout.windowSize(TileShape.RECORDING, 1))
    }

    /** A failure means a cell is not where the layout says: cancel, mic and send side by side along the top of the recording window, the notice's mic below its strip. */
    @Test
    fun `cells at 100 and at 101`() {
        assertEquals("overlay: collapsed mic cell", TileRect(0, 0, 100, 100), TileLayout.micCell(TileShape.COLLAPSED, 100))
        assertEquals("overlay: notice mic cell", TileRect(100, 50, 200, 150), TileLayout.micCell(TileShape.NOTICE, 100))
        assertEquals("overlay: recording mic cell", TileRect(100, 0, 200, 100), TileLayout.micCell(TileShape.RECORDING, 100))
        assertEquals("overlay: cancel cell", TileRect(0, 0, 100, 100), TileLayout.cancelCell(100))
        assertEquals("overlay: send cell", TileRect(200, 0, 300, 100), TileLayout.sendCell(100))
        assertEquals("overlay: notice rectangle", TileRect(0, 0, 300, 50), TileLayout.noticeRect(100))
        assertEquals("overlay: collapsed mic cell at 101", TileRect(0, 0, 101, 101), TileLayout.micCell(TileShape.COLLAPSED, 101))
        assertEquals("overlay: recording mic cell at 101", TileRect(101, 0, 202, 101), TileLayout.micCell(TileShape.RECORDING, 101))
        assertEquals("overlay: cancel cell at 101", TileRect(0, 0, 101, 101), TileLayout.cancelCell(101))
        assertEquals("overlay: send cell at 101", TileRect(202, 0, 303, 101), TileLayout.sendCell(101))
        assertEquals("overlay: notice rectangle at 101", TileRect(0, 0, 303, 50), TileLayout.noticeRect(101))
    }

    /** A failure means, for some tile size, a cell leaves the window, two cells overlap, the three buttons are not side by side on one row, or the notice strip does not sit above the notice's microphone. */
    @Test
    fun `for every tile size the cells fit the window and do not overlap`() {
        for (side in 1..120) {
            val size = TileLayout.windowSize(TileShape.RECORDING, side)
            val window = TileRect(0, 0, size.width, size.height)
            val mic = TileLayout.micCell(TileShape.RECORDING, side)
            val cancel = TileLayout.cancelCell(side)
            val send = TileLayout.sendCell(side)
            val named = listOf("mic" to mic, "cancel" to cancel, "send" to send)
            named.forEach { (name, rect) ->
                assertTrue("overlay: at $side the $name cell $rect expected inside the window $window", inside(rect, window))
            }
            val buttons = listOf("cancel" to cancel, "mic" to mic, "send" to send)
            for (i in buttons.indices) for (j in i + 1 until buttons.size) {
                assertFalse("overlay: at $side the ${buttons[i].first} and ${buttons[j].first} cells expected not to overlap", overlaps(buttons[i].second, buttons[j].second))
            }
            assertEquals("overlay: at $side the cancel cell expected to end where the mic starts", mic.left, cancel.right)
            assertEquals("overlay: at $side the send cell expected to start where the mic ends", mic.right, send.left)
            assertEquals("overlay: at $side the three buttons expected on one row", listOf(mic.top, mic.top), listOf(cancel.top, send.top))
            assertEquals("overlay: at $side the buttons expected to fill the window's height", listOf(0, size.height), listOf(mic.top, mic.bottom))
            val notice = TileLayout.noticeRect(side)
            val noticeWindow = TileLayout.windowSize(TileShape.NOTICE, side)
            assertEquals("overlay: at $side the notice rectangle expected to span the notice window", listOf(0, 0, noticeWindow.width), listOf(notice.left, notice.top, notice.right))
            assertEquals("overlay: at $side the notice rectangle expected to end where the notice's microphone starts", TileLayout.micCell(TileShape.NOTICE, side).top, notice.bottom)
            val collapsed = TileLayout.micCell(TileShape.COLLAPSED, side)
            val tile = TileLayout.windowSize(TileShape.COLLAPSED, side)
            assertEquals("overlay: at $side the collapsed mic cell expected to be the whole window", TileRect(0, 0, tile.width, tile.height), collapsed)
        }
    }

    // ---- shapeOf ----

    /** A failure means the window shape for a state and a notice is not: recording always, else a notice, else the square tile. */
    @Test
    fun `the shape for each state with and without a notice`() {
        for (state in TileState.values()) {
            val expectedWithout = if (state == TileState.RECORDING) TileShape.RECORDING else TileShape.COLLAPSED
            val expectedWith = if (state == TileState.RECORDING) TileShape.RECORDING else TileShape.NOTICE
            assertEquals("overlay: shape for $state with no notice", expectedWithout, shapeOf(state, null))
            assertEquals("overlay: shape for $state with a notice", expectedWith, shapeOf(state, "x"))
        }
        assertEquals("overlay: an empty notice text is still a notice", TileShape.NOTICE, shapeOf(TileState.IDLE, ""))
    }

    // ---- ring and gap sizes ----

    /** A failure means the ring thickness or the gap between meter segments is not the size the view is drawn for. */
    @Test
    fun `ring thickness and meter gap are the sizes the view is drawn for`() {
        assertEquals("overlay: the ring thickness expected 2 dp", 2, TileMetrics.RING_DP)
        assertEquals("overlay: the gap between segments expected 1 pixel", 1, TileMetrics.SEGMENT_GAP_PX)
    }

    // ---- windowOrigin ----

    private val screen = PixelBounds(10, 20, 1010, 2020)

    private fun origin(shape: TileShape, x: Int, y: Int, bounds: PixelBounds = screen) =
        TileLayout.windowOrigin(shape, PixelPoint(x, y), s, bounds)

    /** A failure means the collapsed tile moves, or a window that fits does not sit with its mic cell over the collapsed tile. */
    @Test
    fun `the wide window sits over the collapsed tile and the collapsed tile does not move`() {
        assertEquals("overlay: recording window origin when it fits", PixelPoint(400, 500), origin(TileShape.RECORDING, 500, 500))
        assertEquals("overlay: notice window origin when it fits", PixelPoint(400, 450), origin(TileShape.NOTICE, 500, 500))
        assertEquals("overlay: collapsed origin", PixelPoint(500, 500), origin(TileShape.COLLAPSED, 500, 500))
        assertEquals("overlay: collapsed origin outside the bounds is not changed", PixelPoint(-500, 9999), origin(TileShape.COLLAPSED, -500, 9999))
        assertEquals("overlay: collapsed origin on a small screen is not changed", PixelPoint(3, 4), origin(TileShape.COLLAPSED, 3, 4, PixelBounds(0, 0, 50, 50)))
    }

    /** A failure means a window is allowed to cross a screen edge, or is moved when it already fits, or the edge cases are off by one. */
    @Test
    fun `the wide window is moved only as far as needed to stay inside the bounds`() {
        // The recording window is 300 by 100 inside 10..1010 by 20..2020, so its left edge may be 10..710 and its top edge 20..1920.
        assertEquals("overlay: left edge exactly at the bounds", PixelPoint(10, 500), origin(TileShape.RECORDING, 110, 500))
        assertEquals("overlay: one pixel past the left edge", PixelPoint(10, 500), origin(TileShape.RECORDING, 109, 500))
        assertEquals("overlay: one pixel inside the left edge", PixelPoint(11, 500), origin(TileShape.RECORDING, 111, 500))
        assertEquals("overlay: right edge exactly at the bounds", PixelPoint(710, 500), origin(TileShape.RECORDING, 810, 500))
        assertEquals("overlay: one pixel past the right edge", PixelPoint(710, 500), origin(TileShape.RECORDING, 811, 500))
        assertEquals("overlay: one pixel inside the right edge", PixelPoint(709, 500), origin(TileShape.RECORDING, 809, 500))
        assertEquals("overlay: top edge exactly at the bounds", PixelPoint(400, 20), origin(TileShape.RECORDING, 500, 20))
        assertEquals("overlay: one pixel past the top edge", PixelPoint(400, 20), origin(TileShape.RECORDING, 500, 19))
        assertEquals("overlay: one pixel inside the top edge", PixelPoint(400, 21), origin(TileShape.RECORDING, 500, 21))
        assertEquals("overlay: bottom edge exactly at the bounds", PixelPoint(400, 1920), origin(TileShape.RECORDING, 500, 1920))
        assertEquals("overlay: one pixel past the bottom edge", PixelPoint(400, 1920), origin(TileShape.RECORDING, 500, 1921))
        assertEquals("overlay: one pixel inside the bottom edge", PixelPoint(400, 1919), origin(TileShape.RECORDING, 500, 1919))
        assertEquals("overlay: far past the top left corner", PixelPoint(10, 20), origin(TileShape.RECORDING, -900, -900))
        assertEquals("overlay: far past the bottom right corner", PixelPoint(710, 1870), origin(TileShape.NOTICE, 5000, 5000))
        assertEquals("overlay: a collapsed tile at the bottom right corner of the bounds", PixelPoint(710, 1920), origin(TileShape.RECORDING, 910, 1920))
    }

    /** A failure means a window larger than the bounds is not pinned to the bounds' left and top edges. */
    @Test
    fun `a window larger than the bounds starts at the bounds' left and top edges`() {
        val small = PixelBounds(5, 7, 205, 90)
        assertEquals("overlay: larger on both axes", PixelPoint(5, 7), origin(TileShape.RECORDING, 80, 40, small))
        assertEquals("overlay: larger on both axes, tile far away", PixelPoint(5, 7), origin(TileShape.NOTICE, -50, 500, small))
        val narrow = PixelBounds(0, 0, 200, 1000)
        assertEquals("overlay: wider only, ideal vertical place kept", PixelPoint(0, 500), origin(TileShape.RECORDING, 80, 500, narrow))
        assertEquals("overlay: wider only, vertical clamp", PixelPoint(0, 900), origin(TileShape.RECORDING, 80, 2000, narrow))
        val short = PixelBounds(0, 0, 1000, 90)
        assertEquals("overlay: taller only, ideal horizontal place kept", PixelPoint(300, 0), origin(TileShape.RECORDING, 400, 500, short))
        assertEquals("overlay: exactly as large as the window", PixelPoint(40, 60), origin(TileShape.RECORDING, 500, 500, PixelBounds(40, 60, 340, 160)))
    }

    /** A failure means, wherever the collapsed tile is, the wide window can end up partly off the bounds, or is moved although it fits. */
    @Test
    fun `over a grid of tile positions the window is always inside and moved only when it must be`() {
        for (x in -400..1400 step 37) for (y in -400..2400 step 41) {
            val o = origin(TileShape.RECORDING, x, y)
            assertTrue("overlay: tile at ($x,$y) gave origin $o with the window left of the bounds", o.x >= screen.left)
            assertTrue("overlay: tile at ($x,$y) gave origin $o with the window right of the bounds", o.x + 3 * s <= screen.right)
            assertTrue("overlay: tile at ($x,$y) gave origin $o with the window above the bounds", o.y >= screen.top)
            assertTrue("overlay: tile at ($x,$y) gave origin $o with the window below the bounds", o.y + s <= screen.bottom)
            val fitsX = x - s >= screen.left && x - s + 3 * s <= screen.right
            val fitsY = y >= screen.top && y + s <= screen.bottom
            if (fitsX) assertEquals("overlay: tile at ($x,$y) fits across and expected the ideal left edge", x - s, o.x)
            if (fitsY) assertEquals("overlay: tile at ($x,$y) fits down and expected the ideal top edge", y, o.y)
        }
    }
}
