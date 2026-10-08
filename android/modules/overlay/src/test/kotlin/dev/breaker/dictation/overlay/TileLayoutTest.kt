package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry of the tile window: the rectangle type, the window sizes, the cells, the meter
 * segments, where the wide window goes on the screen, and which shape the app's state asks for.
 * Which zone a touch lands in is in TileLayoutZonesTest.
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
        assertEquals("overlay: recording window at 100", TileSize(300, 150), TileLayout.windowSize(TileShape.RECORDING, 100))
        assertEquals("overlay: collapsed window at 101", TileSize(101, 101), TileLayout.windowSize(TileShape.COLLAPSED, 101))
        assertEquals("overlay: notice window at 101 (strip is 50)", TileSize(303, 151), TileLayout.windowSize(TileShape.NOTICE, 101))
        assertEquals("overlay: recording window at 101 (strip is 50)", TileSize(303, 151), TileLayout.windowSize(TileShape.RECORDING, 101))
        assertEquals("overlay: recording window at 1 (strip is 0)", TileSize(3, 1), TileLayout.windowSize(TileShape.RECORDING, 1))
    }

    /** A failure means a cell is not where the layout says: cancel, mic and send side by side below the strip, the meter above the mic. */
    @Test
    fun `cells at 100 and at 101`() {
        assertEquals("overlay: collapsed mic cell", TileRect(0, 0, 100, 100), TileLayout.micCell(TileShape.COLLAPSED, 100))
        assertEquals("overlay: notice mic cell", TileRect(100, 50, 200, 150), TileLayout.micCell(TileShape.NOTICE, 100))
        assertEquals("overlay: recording mic cell", TileRect(100, 50, 200, 150), TileLayout.micCell(TileShape.RECORDING, 100))
        assertEquals("overlay: cancel cell", TileRect(0, 50, 100, 150), TileLayout.cancelCell(100))
        assertEquals("overlay: send cell", TileRect(200, 50, 300, 150), TileLayout.sendCell(100))
        assertEquals("overlay: meter rectangle", TileRect(100, 0, 200, 50), TileLayout.meterRect(100))
        assertEquals("overlay: notice rectangle", TileRect(0, 0, 300, 50), TileLayout.noticeRect(100))
        assertEquals("overlay: collapsed mic cell at 101", TileRect(0, 0, 101, 101), TileLayout.micCell(TileShape.COLLAPSED, 101))
        assertEquals("overlay: recording mic cell at 101", TileRect(101, 50, 202, 151), TileLayout.micCell(TileShape.RECORDING, 101))
        assertEquals("overlay: cancel cell at 101", TileRect(0, 50, 101, 151), TileLayout.cancelCell(101))
        assertEquals("overlay: send cell at 101", TileRect(202, 50, 303, 151), TileLayout.sendCell(101))
        assertEquals("overlay: meter rectangle at 101", TileRect(101, 0, 202, 50), TileLayout.meterRect(101))
        assertEquals("overlay: notice rectangle at 101", TileRect(0, 0, 303, 50), TileLayout.noticeRect(101))
    }

    /** A failure means, for some tile size, a cell leaves the window, two cells overlap, the three buttons are not side by side or the meter is not directly above the mic. */
    @Test
    fun `for every tile size the cells fit the window and do not overlap`() {
        for (side in 1..120) {
            val size = TileLayout.windowSize(TileShape.RECORDING, side)
            val window = TileRect(0, 0, size.width, size.height)
            val mic = TileLayout.micCell(TileShape.RECORDING, side)
            val cancel = TileLayout.cancelCell(side)
            val send = TileLayout.sendCell(side)
            val meter = TileLayout.meterRect(side)
            val notice = TileLayout.noticeRect(side)
            val named = listOf("mic" to mic, "cancel" to cancel, "send" to send, "meter" to meter, "notice" to notice)
            named.forEach { (name, rect) ->
                assertTrue("overlay: at $side the $name cell $rect expected inside the window $window", inside(rect, window))
            }
            val buttons = listOf("cancel" to cancel, "mic" to mic, "send" to send)
            for (i in buttons.indices) for (j in i + 1 until buttons.size) {
                assertFalse("overlay: at $side the ${buttons[i].first} and ${buttons[j].first} cells expected not to overlap", overlaps(buttons[i].second, buttons[j].second))
            }
            assertFalse("overlay: at $side the meter and the mic expected not to overlap", overlaps(meter, mic))
            assertEquals("overlay: at $side the cancel cell expected to end where the mic starts", mic.left, cancel.right)
            assertEquals("overlay: at $side the send cell expected to start where the mic ends", mic.right, send.left)
            assertEquals("overlay: at $side the three buttons expected on one row", listOf(mic.top, mic.top), listOf(cancel.top, send.top))
            assertEquals("overlay: at $side the meter expected directly above the mic", mic.top, meter.bottom)
            assertEquals("overlay: at $side the meter expected to span the mic's width", listOf(mic.left, mic.right), listOf(meter.left, meter.right))
            assertEquals("overlay: at $side the notice rectangle expected to span the window", listOf(0, 0, size.width), listOf(notice.left, notice.top, notice.right))
            assertEquals("overlay: at $side the notice rectangle expected to end where the buttons start", mic.top, notice.bottom)
            assertEquals("overlay: at $side the buttons expected to end at the window's bottom", size.height, mic.bottom)
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

    /** A failure means the ring or the gap between meter segments is not the size the view is drawn for, or the gap swallows the smallest segment. */
    @Test
    fun `ring and gap sizes leave every meter segment something to show`() {
        assertEquals("overlay: the ring thickness expected 2 dp", 2, TileMetrics.RING_DP)
        assertEquals("overlay: the gap between segments expected 1 pixel", 1, TileMetrics.SEGMENT_GAP_PX)
        // The smallest tile is 56 pixels (one pixel per dp), so the meter is 56 pixels wide.
        val widths = TileLayout.segmentRects(LedMeter.SEGMENTS, TileLayout.meterRect(TileMetrics.TILE_SIZE_DP)).map { it.width }
        assertTrue(
            "overlay: the narrowest of $widths expected to stay wider than a gap on both sides",
            widths.min() > 2 * TileMetrics.SEGMENT_GAP_PX,
        )
    }

    // ---- segmentRects ----

    /** A failure means the segments leave a gap, overlap, do not add up to the meter's width, differ by more than one pixel, or change height. */
    @Test
    fun `segments partition the meter exactly for widths 0 to 200 and counts 1 to 16`() {
        for (width in 0..200) {
            val meter = TileRect(7, 3, 7 + width, 8)
            for (count in 1..16) {
                val cells = TileLayout.segmentRects(count, meter)
                val what = "width $width count $count"
                assertEquals("overlay: $what expected $count cells", count, cells.size)
                assertEquals("overlay: $what first cell expected to start at the meter's left edge", meter.left, cells.first().left)
                assertEquals("overlay: $what last cell expected to end at the meter's right edge", meter.right, cells.last().right)
                for (k in 0 until count - 1) {
                    assertEquals("overlay: $what cell $k expected to end where cell ${k + 1} starts", cells[k + 1].left, cells[k].right)
                }
                assertEquals("overlay: $what widths expected to add up to the meter's width", width, cells.sumOf { it.width })
                assertTrue("overlay: $what widths ${cells.map { it.width }} expected to differ by at most 1", cells.maxOf { it.width } - cells.minOf { it.width } <= 1)
                assertTrue("overlay: $what a cell expected not to have a negative width", cells.all { it.width >= 0 })
                assertTrue("overlay: $what every cell expected to have the meter's full height", cells.all { it.top == meter.top && it.bottom == meter.bottom })
            }
        }
    }

    /** A failure means an exact division gives uneven cells, or no cells are returned for a count of zero or less. */
    @Test
    fun `segments of an exact division and a count of nothing`() {
        val twelve = TileLayout.segmentRects(12, TileRect(100, 0, 124, 50))
        assertEquals("overlay: 24 pixels in 12 cells expected 2 pixels each", List(12) { 2 }, twelve.map { it.width })
        assertEquals("overlay: the first of 12 cells", TileRect(100, 0, 102, 50), twelve.first())
        assertEquals("overlay: the last of 12 cells", TileRect(122, 0, 124, 50), twelve.last())
        assertEquals("overlay: 12 pixels in 4 cells expected 3 pixels each", listOf(3, 3, 3, 3), TileLayout.segmentRects(4, TileRect(0, 0, 12, 5)).map { it.width })
        assertEquals("overlay: one cell expected to be the whole meter", listOf(TileRect(5, 6, 55, 9)), TileLayout.segmentRects(1, TileRect(5, 6, 55, 9)))
        listOf(0, -1, -16).forEach { count ->
            assertEquals("overlay: a count of $count expected no cells", emptyList<TileRect>(), TileLayout.segmentRects(count, TileRect(0, 0, 50, 50)))
        }
    }

    // ---- windowOrigin ----

    private val screen = PixelBounds(10, 20, 1010, 2020)

    private fun origin(shape: TileShape, x: Int, y: Int, bounds: PixelBounds = screen) =
        TileLayout.windowOrigin(shape, PixelPoint(x, y), s, bounds)

    /** A failure means the collapsed tile moves, or a window that fits does not sit with its mic cell over the collapsed tile. */
    @Test
    fun `the wide window sits over the collapsed tile and the collapsed tile does not move`() {
        assertEquals("overlay: recording window origin when it fits", PixelPoint(400, 450), origin(TileShape.RECORDING, 500, 500))
        assertEquals("overlay: notice window origin when it fits", PixelPoint(400, 450), origin(TileShape.NOTICE, 500, 500))
        assertEquals("overlay: collapsed origin", PixelPoint(500, 500), origin(TileShape.COLLAPSED, 500, 500))
        assertEquals("overlay: collapsed origin outside the bounds is not changed", PixelPoint(-500, 9999), origin(TileShape.COLLAPSED, -500, 9999))
        assertEquals("overlay: collapsed origin on a small screen is not changed", PixelPoint(3, 4), origin(TileShape.COLLAPSED, 3, 4, PixelBounds(0, 0, 50, 50)))
    }

    /** A failure means a window is allowed to cross a screen edge, or is moved when it already fits, or the edge cases are off by one. */
    @Test
    fun `the wide window is moved only as far as needed to stay inside the bounds`() {
        // The window is 300 by 150 inside 10..1010 by 20..2020, so its left edge may be 10..710 and its top edge 20..1870.
        assertEquals("overlay: left edge exactly at the bounds", PixelPoint(10, 450), origin(TileShape.RECORDING, 110, 500))
        assertEquals("overlay: one pixel past the left edge", PixelPoint(10, 450), origin(TileShape.RECORDING, 109, 500))
        assertEquals("overlay: one pixel inside the left edge", PixelPoint(11, 450), origin(TileShape.RECORDING, 111, 500))
        assertEquals("overlay: right edge exactly at the bounds", PixelPoint(710, 450), origin(TileShape.RECORDING, 810, 500))
        assertEquals("overlay: one pixel past the right edge", PixelPoint(710, 450), origin(TileShape.RECORDING, 811, 500))
        assertEquals("overlay: one pixel inside the right edge", PixelPoint(709, 450), origin(TileShape.RECORDING, 809, 500))
        assertEquals("overlay: top edge exactly at the bounds", PixelPoint(400, 20), origin(TileShape.RECORDING, 500, 70))
        assertEquals("overlay: one pixel past the top edge", PixelPoint(400, 20), origin(TileShape.RECORDING, 500, 69))
        assertEquals("overlay: one pixel inside the top edge", PixelPoint(400, 21), origin(TileShape.RECORDING, 500, 71))
        assertEquals("overlay: bottom edge exactly at the bounds", PixelPoint(400, 1870), origin(TileShape.RECORDING, 500, 1920))
        assertEquals("overlay: one pixel past the bottom edge", PixelPoint(400, 1870), origin(TileShape.RECORDING, 500, 1921))
        assertEquals("overlay: one pixel inside the bottom edge", PixelPoint(400, 1869), origin(TileShape.RECORDING, 500, 1919))
        assertEquals("overlay: far past the top left corner", PixelPoint(10, 20), origin(TileShape.RECORDING, -900, -900))
        assertEquals("overlay: far past the bottom right corner", PixelPoint(710, 1870), origin(TileShape.NOTICE, 5000, 5000))
        assertEquals("overlay: a collapsed tile at the bottom right corner of the bounds", PixelPoint(710, 1870), origin(TileShape.RECORDING, 910, 1920))
    }

    /** A failure means a window larger than the bounds is not pinned to the bounds' left and top edges. */
    @Test
    fun `a window larger than the bounds starts at the bounds' left and top edges`() {
        val small = PixelBounds(5, 7, 205, 107)
        assertEquals("overlay: larger on both axes", PixelPoint(5, 7), origin(TileShape.RECORDING, 80, 40, small))
        assertEquals("overlay: larger on both axes, tile far away", PixelPoint(5, 7), origin(TileShape.NOTICE, -50, 500, small))
        val narrow = PixelBounds(0, 0, 200, 1000)
        assertEquals("overlay: wider only, ideal vertical place kept", PixelPoint(0, 450), origin(TileShape.RECORDING, 80, 500, narrow))
        assertEquals("overlay: wider only, vertical clamp", PixelPoint(0, 850), origin(TileShape.RECORDING, 80, 2000, narrow))
        val short = PixelBounds(0, 0, 1000, 100)
        assertEquals("overlay: taller only, ideal horizontal place kept", PixelPoint(300, 0), origin(TileShape.RECORDING, 400, 500, short))
        assertEquals("overlay: exactly as large as the window", PixelPoint(40, 60), origin(TileShape.RECORDING, 500, 500, PixelBounds(40, 60, 340, 210)))
    }

    /** A failure means, wherever the collapsed tile is, the wide window can end up partly off the bounds, or is moved although it fits. */
    @Test
    fun `over a grid of tile positions the window is always inside and moved only when it must be`() {
        for (x in -400..1400 step 37) for (y in -400..2400 step 41) {
            val o = origin(TileShape.RECORDING, x, y)
            assertTrue("overlay: tile at ($x,$y) gave origin $o with the window left of the bounds", o.x >= screen.left)
            assertTrue("overlay: tile at ($x,$y) gave origin $o with the window right of the bounds", o.x + 3 * s <= screen.right)
            assertTrue("overlay: tile at ($x,$y) gave origin $o with the window above the bounds", o.y >= screen.top)
            assertTrue("overlay: tile at ($x,$y) gave origin $o with the window below the bounds", o.y + s + s / 2 <= screen.bottom)
            val fitsX = x - s >= screen.left && x - s + 3 * s <= screen.right
            val fitsY = y - s / 2 >= screen.top && y - s / 2 + s + s / 2 <= screen.bottom
            if (fitsX) assertEquals("overlay: tile at ($x,$y) fits across and expected the ideal left edge", x - s, o.x)
            if (fitsY) assertEquals("overlay: tile at ($x,$y) fits down and expected the ideal top edge", y - s / 2, o.y)
        }
    }
}
