package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The placement arithmetic, on screens whose usable area does not start at 0,0.
 *
 * Every expected number is worked out by hand in the comments next to it, never by calling the code
 * under test. The main screen is x 16..1096 and y 80..2300 (1080 by 2220) with a tile of 100, so the
 * tile may move over 980 by 2120 pixels, and its top-left corner is allowed at x 16..996, y 80..2200.
 */
class TilePlacementTest {
    private val screen = PixelBounds(16, 80, 1096, 2300)
    private val tile = 100

    private fun assertPoint(what: String, expectedX: Int, expectedY: Int, actual: PixelPoint) {
        assertEquals("android_overlay: $what: expected x $expectedX", expectedX, actual.x)
        assertEquals("android_overlay: $what: expected y $expectedY", expectedY, actual.y)
    }

    private fun assertFraction(what: String, expectedX: Float, expectedY: Float, actual: TilePosition) {
        assertEquals("android_overlay: $what: expected fraction x $expectedX", expectedX, actual.x, 0.0001f)
        assertEquals("android_overlay: $what: expected fraction y $expectedY", expectedY, actual.y, 0.0001f)
    }

    /** A failure means the movable range is not the usable size minus the tile size, per axis. */
    @Test
    fun `the movable range is the usable area minus the tile size`() {
        // 1096 - 16 = 1080, minus 100 = 980; 2300 - 80 = 2220, minus 100 = 2120.
        assertEquals("android_overlay: movable width of a 1080 wide area with a 100 tile expected 980", 980, TilePlacement.movableWidth(screen, tile))
        assertEquals("android_overlay: movable height of a 2220 high area with a 100 tile expected 2120", 2120, TilePlacement.movableHeight(screen, tile))

        // 110 - 10 = 100, minus 40 = 60; 220 - 20 = 200, minus 40 = 160.
        val small = PixelBounds(10, 20, 110, 220)
        assertEquals("android_overlay: movable width of a 100 wide area with a 40 tile expected 60", 60, TilePlacement.movableWidth(small, 40))
        assertEquals("android_overlay: movable height of a 200 high area with a 40 tile expected 160", 160, TilePlacement.movableHeight(small, 40))

        // 500 - 0 = 500, minus 56 = 444; 300 - 0 = 300, minus 56 = 244.
        val phone = PixelBounds(0, 0, 500, 300)
        assertEquals("android_overlay: movable width of a 500 wide area with a 56 tile expected 444", 444, TilePlacement.movableWidth(phone, 56))
        assertEquals("android_overlay: movable height of a 300 high area with a 56 tile expected 244", 244, TilePlacement.movableHeight(phone, 56))
    }

    /** A failure means a fraction no longer lands on the origin plus its share of the movable range. */
    @Test
    fun `a fraction maps to pixels over the movable range from the usable origin`() {
        // Corners: x is 16 or 16 + 980 = 996, y is 80 or 80 + 2120 = 2200.
        assertPoint("fraction 0,0 is the top-left corner", 16, 80, TilePlacement.toPixels(TilePosition(0f, 0f), screen, tile))
        assertPoint("fraction 1,1 is the bottom-right corner", 996, 2200, TilePlacement.toPixels(TilePosition(1f, 1f), screen, tile))
        assertPoint("fraction 1,0 is the top-right corner", 996, 80, TilePlacement.toPixels(TilePosition(1f, 0f), screen, tile))
        assertPoint("fraction 0,1 is the bottom-left corner", 16, 2200, TilePlacement.toPixels(TilePosition(0f, 1f), screen, tile))
        // Centre: 16 + 490 = 506, 80 + 1060 = 1140.
        assertPoint("fraction 0.5,0.5 is the centre", 506, 1140, TilePlacement.toPixels(TilePosition(0.5f, 0.5f), screen, tile))
        // 0.25 * 980 = 245 so x = 261; 0.75 * 2120 = 1590 so y = 1670.
        assertPoint("fraction 0.25,0.75", 261, 1670, TilePlacement.toPixels(TilePosition(0.25f, 0.75f), screen, tile))
    }

    /** A failure means a half pixel is not rounded up, or a fraction below or above a half is not rounded to the nearest. */
    @Test
    fun `a half fraction over an odd range rounds half up`() {
        // Width 1101 and height 2001 with a tile of 100: movable 1001 by 1901, origin 0,0.
        val odd = PixelBounds(0, 0, 1101, 2001)
        // 0.5 * 1001 = 500.5 -> 501; 0.5 * 1901 = 950.5 -> 951.
        assertPoint("a half over an odd range", 501, 951, TilePlacement.toPixels(TilePosition(0.5f, 0.5f), odd, tile))
        // 0.25 * 1001 = 250.25 -> 250 (down); 0.75 * 1901 = 1425.75 -> 1426 (up).
        assertPoint("a quarter and three quarters over an odd range", 250, 1426, TilePlacement.toPixels(TilePosition(0.25f, 0.75f), odd, tile))
        // The same screen moved to a non-zero origin adds the origin after rounding: 10 + 501, 20 + 951.
        val shifted = PixelBounds(10, 20, 1111, 2021)
        assertPoint("a half over an odd range from a non-zero origin", 511, 971, TilePlacement.toPixels(TilePosition(0.5f, 0.5f), shifted, tile))
        // Movable range 1: 0.5 * 1 = 0.5 -> 1 (round half up, not half to even, which would give 0).
        val one = PixelBounds(0, 0, 101, 101)
        assertPoint("a half over a movable range of 1", 1, 1, TilePlacement.toPixels(TilePosition(0.5f, 0.5f), one, tile))
        // Movable range 3: 0.5 * 3 = 1.5 -> 2.
        val three = PixelBounds(0, 0, 103, 103)
        assertPoint("a half over a movable range of 3", 2, 2, TilePlacement.toPixels(TilePosition(0.5f, 0.5f), three, tile))
    }

    /** A failure means a point outside the allowed range is not pulled back to the nearest edge on that axis. */
    @Test
    fun `clamp keeps the whole tile inside the usable area on all four sides`() {
        // Allowed x 16..996, y 80..2200. Each case leaves the other axis inside.
        assertPoint("left of the area", 16, 1000, TilePlacement.clamp(-50, 1000, screen, tile))
        assertPoint("right of the area", 996, 1000, TilePlacement.clamp(2000, 1000, screen, tile))
        assertPoint("above the area", 500, 80, TilePlacement.clamp(500, -50, screen, tile))
        assertPoint("below the area", 500, 2200, TilePlacement.clamp(500, 5000, screen, tile))
        // Both axes out at once.
        assertPoint("past the top-left corner", 16, 80, TilePlacement.clamp(-1, -1, screen, tile))
        assertPoint("past the bottom-right corner", 996, 2200, TilePlacement.clamp(5000, 5000, screen, tile))
        // One pixel past each edge: 15 -> 16, 997 -> 996, 79 -> 80, 2201 -> 2200.
        assertPoint("one pixel past the left and top edges", 16, 80, TilePlacement.clamp(15, 79, screen, tile))
        assertPoint("one pixel past the right and bottom edges", 996, 2200, TilePlacement.clamp(997, 2201, screen, tile))
    }

    /** A failure means clamp moves a point that was already allowed, including one exactly on an edge. */
    @Test
    fun `clamp leaves a point that is already inside unchanged`() {
        assertPoint("a point well inside", 500, 1000, TilePlacement.clamp(500, 1000, screen, tile))
        assertPoint("the top-left allowed corner", 16, 80, TilePlacement.clamp(16, 80, screen, tile))
        assertPoint("the bottom-right allowed corner", 996, 2200, TilePlacement.clamp(996, 2200, screen, tile))
        assertPoint("one pixel inside the top-left corner", 17, 81, TilePlacement.clamp(17, 81, screen, tile))
        assertPoint("one pixel inside the bottom-right corner", 995, 2199, TilePlacement.clamp(995, 2199, screen, tile))
    }

    /** A failure means saving a position and restoring it gives a different place, or the fraction is not offset by the origin. */
    @Test
    fun `pixels and fraction round-trip on two different screens`() {
        // Literal fractions on the main screen: (506 - 16) / 980 = 0.5, (1140 - 80) / 2120 = 0.5.
        assertFraction("the centre pixel", 0.5f, 0.5f, TilePlacement.toPosition(506, 1140, screen, tile))
        // (261 - 16) / 980 = 0.25, (1670 - 80) / 2120 = 0.75.
        assertFraction("the quarter pixel", 0.25f, 0.75f, TilePlacement.toPosition(261, 1670, screen, tile))
        assertFraction("the top-left corner", 0f, 0f, TilePlacement.toPosition(16, 80, screen, tile))
        assertFraction("the bottom-right corner", 1f, 1f, TilePlacement.toPosition(996, 2200, screen, tile))

        val main = listOf(
            PixelPoint(16, 80), PixelPoint(996, 2200), PixelPoint(506, 1140),
            PixelPoint(17, 81), PixelPoint(995, 2199), PixelPoint(300, 777),
        )
        for (point in main) {
            val back = TilePlacement.toPixels(TilePlacement.toPosition(point.x, point.y, screen, tile), screen, tile)
            assertEquals("android_overlay: main screen round trip of $point expected the same pixel", point, back)
        }

        // A second screen, 720 wide from x 0 and 1417 high from y 63, tile 84: movable 636 by 1333.
        val other = PixelBounds(0, 63, 720, 1480)
        val otherPoints = listOf(
            PixelPoint(0, 63), PixelPoint(636, 1396), PixelPoint(200, 700),
            PixelPoint(1, 64), PixelPoint(635, 1395),
        )
        for (point in otherPoints) {
            val back = TilePlacement.toPixels(TilePlacement.toPosition(point.x, point.y, other, 84), other, 84)
            assertEquals("android_overlay: second screen round trip of $point expected the same pixel", point, back)
        }

        // A third screen with odd movable ranges 1001 by 1901 and a non-zero origin.
        val odd = PixelBounds(10, 20, 1111, 2021)
        val oddPoints = listOf(PixelPoint(10, 20), PixelPoint(1011, 1921), PixelPoint(510, 970), PixelPoint(511, 971))
        for (point in oddPoints) {
            val back = TilePlacement.toPixels(TilePlacement.toPosition(point.x, point.y, odd, tile), odd, tile)
            assertEquals("android_overlay: odd screen round trip of $point expected the same pixel", point, back)
        }
    }

    /** A failure means a pixel outside the allowed range gives a fraction outside 0..1 (or throws) instead of 0 or 1. */
    @Test
    fun `a pixel position outside the range gives a fraction clamped to 0 and 1`() {
        assertFraction("far past the top-left", 0f, 0f, TilePlacement.toPosition(-100, -100, screen, tile))
        assertFraction("far past the bottom-right", 1f, 1f, TilePlacement.toPosition(5000, 5000, screen, tile))
        assertFraction("far right and far above", 1f, 0f, TilePlacement.toPosition(5000, -5000, screen, tile))
        assertFraction("far left and far below", 0f, 1f, TilePlacement.toPosition(-5000, 5000, screen, tile))
        // One pixel before the origin would be (15 - 16) / 980 below 0, and one past the end above 1.
        assertFraction("one pixel before the origin", 0f, 0f, TilePlacement.toPosition(15, 79, screen, tile))
        assertFraction("one pixel past the far edge", 1f, 1f, TilePlacement.toPosition(997, 2201, screen, tile))
    }

    /** A failure means an area with no room to move divides by zero, gives a fraction other than 0, or leaves the origin. */
    @Test
    fun `a zero movable range gives fraction 0 and the origin`() {
        // 116 - 16 = 100 wide and 180 - 80 = 100 high with a tile of 100: nothing to move over.
        val none = PixelBounds(16, 80, 116, 180)
        assertEquals("android_overlay: movable width expected 0 when the area is exactly the tile", 0, TilePlacement.movableWidth(none, tile))
        assertEquals("android_overlay: movable height expected 0 when the area is exactly the tile", 0, TilePlacement.movableHeight(none, tile))
        assertFraction("the origin with no room", 0f, 0f, TilePlacement.toPosition(16, 80, none, tile))
        assertFraction("a far pixel with no room", 0f, 0f, TilePlacement.toPosition(500, 500, none, tile))
        assertPoint("fraction 0.5,0.5 with no room", 16, 80, TilePlacement.toPixels(TilePosition(0.5f, 0.5f), none, tile))
        assertPoint("fraction 1,1 with no room", 16, 80, TilePlacement.toPixels(TilePosition(1f, 1f), none, tile))
        assertPoint("clamp with no room", 16, 80, TilePlacement.clamp(500, 500, none, tile))

        // Only the width has no room: 100 wide from x 16, 2220 high from y 80. Height still has 2120.
        val narrow = PixelBounds(16, 80, 116, 2300)
        assertPoint("fraction 1,1 with no horizontal room", 16, 2200, TilePlacement.toPixels(TilePosition(1f, 1f), narrow, tile))
        assertFraction("a pixel mid-height with no horizontal room", 0f, 0.5f, TilePlacement.toPosition(300, 1140, narrow, tile))
    }

    /** A failure means a tile bigger than the area gives a negative range, a negative pixel offset, or a position left of the origin. */
    @Test
    fun `a tile larger than the usable area never gives a negative range`() {
        // 100 by 100 area with a tile of 150: the range is 0, not -50.
        val small = PixelBounds(16, 80, 116, 180)
        assertEquals("android_overlay: movable width expected 0 for a tile wider than the area", 0, TilePlacement.movableWidth(small, 150))
        assertEquals("android_overlay: movable height expected 0 for a tile higher than the area", 0, TilePlacement.movableHeight(small, 150))
        assertPoint("fraction 0.5,0.5 for an oversized tile stays at the origin", 16, 80, TilePlacement.toPixels(TilePosition(0.5f, 0.5f), small, 150))
        assertPoint("fraction 1,1 for an oversized tile stays at the origin", 16, 80, TilePlacement.toPixels(TilePosition(1f, 1f), small, 150))
        assertPoint("clamp from the left for an oversized tile", 16, 80, TilePlacement.clamp(0, 0, small, 150))
        assertPoint("clamp from the right for an oversized tile", 16, 80, TilePlacement.clamp(500, 500, small, 150))
        assertFraction("the origin for an oversized tile", 0f, 0f, TilePlacement.toPosition(16, 80, small, 150))

        // Only one axis oversized: 50 wide, 1000 high, tile 100 -> width range 0, height range 900.
        val tall = PixelBounds(0, 0, 50, 1000)
        assertEquals("android_overlay: movable width expected 0 for a 50 wide area and a 100 tile", 0, TilePlacement.movableWidth(tall, tile))
        assertEquals("android_overlay: movable height expected 900 for a 1000 high area and a 100 tile", 900, TilePlacement.movableHeight(tall, tile))
        assertPoint("fraction 1,1 with only the height oversized", 0, 900, TilePlacement.toPixels(TilePosition(1f, 1f), tall, tile))

        // An empty area (right = left, bottom = top) is also a zero range.
        val empty = PixelBounds(16, 80, 16, 80)
        assertEquals("android_overlay: movable width expected 0 for an empty area", 0, TilePlacement.movableWidth(empty, tile))
        assertEquals("android_overlay: movable height expected 0 for an empty area", 0, TilePlacement.movableHeight(empty, tile))
    }

    /** A failure means a box with an edge before its opposite edge is accepted on one axis, or an empty box is refused. */
    @Test
    fun `an inverted pixel box is refused and an empty one is accepted`() {
        // Right is one less than left (15 < 16), so the width would be -1; the height is 80 - 80 = 0.
        try {
            val built = PixelBounds(16, 80, 15, 80)
            fail("android_overlay: a box with right 15 left of left 16 expected to be refused, got $built")
        } catch (e: IllegalArgumentException) {
            // Refused as expected; the AssertionError from fail() is not an IllegalArgumentException and passes through.
        }
        // Bottom is one less than top (79 < 80), so the height would be -1; the width is 16 - 16 = 0.
        try {
            val built = PixelBounds(16, 80, 16, 79)
            fail("android_overlay: a box with bottom 79 above top 80 expected to be refused, got $built")
        } catch (e: IllegalArgumentException) {
            // Refused as expected.
        }
        // Equal edges give an empty box: 16 - 16 = 0 wide and 80 - 80 = 0 high.
        val empty = PixelBounds(16, 80, 16, 80)
        assertEquals("android_overlay: width expected 0 for equal left and right edges", 0, empty.width)
        assertEquals("android_overlay: height expected 0 for equal top and bottom edges", 0, empty.height)
        // One pixel each way: 17 - 16 = 1 wide and 81 - 80 = 1 high.
        val unit = PixelBounds(16, 80, 17, 81)
        assertEquals("android_overlay: width expected 1 for edges one apart", 1, unit.width)
        assertEquals("android_overlay: height expected 1 for edges one apart", 1, unit.height)
    }
}
