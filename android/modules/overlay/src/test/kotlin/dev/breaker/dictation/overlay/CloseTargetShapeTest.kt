package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry tests for the close target box: its art constants, its window sizes, its scale and
 * its hit test. They run on a plain JVM and use no Android types. Every number is worked out in a
 * comment, so a wrong value points at the rule that broke.
 */
class CloseTargetShapeTest {

    /** The scale used for the box tests: half width 183, half height 48, corner 13 pixels. */
    private val half = 0.5f
    private val halfW = CloseTargetShape.halfWidthPx(half)
    private val halfH = CloseTargetShape.halfHeightPx(half)
    private val corner = CloseTargetShape.cornerPx(half)

    @Test
    fun `the eight art constants have the design values`() {
        assertEquals("overlay: ART_WIDTH is 740", 740f, CloseTargetShape.ART_WIDTH, 0f)
        assertEquals("overlay: ART_HEIGHT is 200", 200f, CloseTargetShape.ART_HEIGHT, 0f)
        assertEquals("overlay: ART_MARGIN is 4", 4f, CloseTargetShape.ART_MARGIN, 0f)
        assertEquals("overlay: ART_CORNER is 26", 26f, CloseTargetShape.ART_CORNER, 0f)
        assertEquals("overlay: EDGE_IDLE_ART is 3", 3f, CloseTargetShape.EDGE_IDLE_ART, 0f)
        assertEquals("overlay: EDGE_ACTIVE_ART is 5", 5f, CloseTargetShape.EDGE_ACTIVE_ART, 0f)
        assertEquals("overlay: WIDTH_FRACTION is 0.47", 0.47f, CloseTargetShape.WIDTH_FRACTION, 0f)
        assertEquals("overlay: HEIGHT_FRACTION is 0.059", 0.059f, CloseTargetShape.HEIGHT_FRACTION, 0f)
    }

    @Test
    fun `window size for 1080 by 2400 bounds is 507 by 141`() {
        val bounds = PixelBounds(0, 0, 1080, 2400)
        assertEquals("overlay: 0.47 of 1080 is 507.6, truncated to 507", 507, CloseTargetShape.windowWidthPx(bounds))
        assertEquals("overlay: 0.059 of 2400 is 141.6, truncated to 141", 141, CloseTargetShape.windowHeightPx(bounds))
    }

    @Test
    fun `window size counts only the width and height of bounds that start away from zero`() {
        val bounds = PixelBounds(100, 200, 1180, 2600)
        assertEquals("overlay: the left and top do not change the width", 507, CloseTargetShape.windowWidthPx(bounds))
        assertEquals("overlay: the left and top do not change the height", 141, CloseTargetShape.windowHeightPx(bounds))
    }

    @Test
    fun `window size of tiny bounds truncates and goes to zero`() {
        val tiny = PixelBounds(0, 0, 10, 10)
        assertEquals("overlay: 0.47 of 10 is 4.7, truncated to 4", 4, CloseTargetShape.windowWidthPx(tiny))
        assertEquals("overlay: 0.059 of 10 is 0.59, truncated to 0", 0, CloseTargetShape.windowHeightPx(tiny))
        assertEquals("overlay: empty bounds give a zero width", 0, CloseTargetShape.windowWidthPx(PixelBounds(5, 5, 5, 5)))
    }

    @Test
    fun `scale is the width ratio when the width is the smaller ratio`() {
        // 200 / 740 is about 0.27 and 1000 / 200 is 5, so the width ratio is the smaller one.
        assertEquals("overlay: scale takes the width ratio", 200f / 740f, CloseTargetShape.scale(200, 1000), 0f)
    }

    @Test
    fun `scale is the height ratio when the height is the smaller ratio`() {
        // 1000 / 740 is about 1.35 and 100 / 200 is 0.5, so the height ratio is the smaller one.
        assertEquals("overlay: scale takes the height ratio", 0.5f, CloseTargetShape.scale(1000, 100), 0f)
    }

    @Test
    fun `scale is exactly the smaller of the two ratios`() {
        assertEquals(
            "overlay: scale is the exact minimum of width over 740 and height over 200",
            minOf(507f / 740f, 141f / 200f),
            CloseTargetShape.scale(507, 141),
            0f,
        )
    }

    @Test
    fun `scale is zero when either window side is zero or negative`() {
        assertEquals("overlay: zero width gives scale 0", 0f, CloseTargetShape.scale(0, 141), 0f)
        assertEquals("overlay: zero height gives scale 0", 0f, CloseTargetShape.scale(507, 0), 0f)
        assertEquals("overlay: negative width gives scale 0", 0f, CloseTargetShape.scale(-1, 141), 0f)
        assertEquals("overlay: negative height gives scale 0", 0f, CloseTargetShape.scale(507, -1), 0f)
    }

    @Test
    fun `half width half height and corner are the stated products`() {
        assertEquals("overlay: half width is (370 - 4) times 0.5, which is 183", 183f, halfW, 0f)
        assertEquals("overlay: half height is (100 - 4) times 0.5, which is 48", 48f, halfH, 0f)
        assertEquals("overlay: corner is 26 times 0.5, which is 13", 13f, corner, 0f)
    }

    @Test
    fun `the centre is inside`() {
        assertTrue("overlay: the box centre is inside", CloseTargetShape.contains(0f, 0f, half))
    }

    @Test
    fun `points one pixel inside each side are inside and one pixel outside are outside`() {
        assertTrue("overlay: one pixel inside the right side", CloseTargetShape.contains(halfW - 1f, 0f, half))
        assertFalse("overlay: one pixel outside the right side", CloseTargetShape.contains(halfW + 1f, 0f, half))
        assertTrue("overlay: one pixel inside the left side", CloseTargetShape.contains(-(halfW - 1f), 0f, half))
        assertFalse("overlay: one pixel outside the left side", CloseTargetShape.contains(-(halfW + 1f), 0f, half))
        assertTrue("overlay: one pixel inside the top side", CloseTargetShape.contains(0f, -(halfH - 1f), half))
        assertFalse("overlay: one pixel outside the top side", CloseTargetShape.contains(0f, -(halfH + 1f), half))
        assertTrue("overlay: one pixel inside the bottom side", CloseTargetShape.contains(0f, halfH - 1f, half))
        assertFalse("overlay: one pixel outside the bottom side", CloseTargetShape.contains(0f, halfH + 1f, half))
    }

    @Test
    fun `a point exactly on the right edge and on the top edge is inside`() {
        assertTrue("overlay: a point exactly on the right edge is inside", CloseTargetShape.contains(halfW, 0f, half))
        assertTrue("overlay: a point exactly on the top edge is inside", CloseTargetShape.contains(0f, -halfH, half))
    }

    @Test
    fun `the four corners of the bounding box are outside`() {
        assertFalse("overlay: top right corner of the bounding box is outside", CloseTargetShape.contains(halfW, -halfH, half))
        assertFalse("overlay: top left corner of the bounding box is outside", CloseTargetShape.contains(-halfW, -halfH, half))
        assertFalse("overlay: bottom right corner of the bounding box is outside", CloseTargetShape.contains(halfW, halfH, half))
        assertFalse("overlay: bottom left corner of the bounding box is outside", CloseTargetShape.contains(-halfW, halfH, half))
    }

    @Test
    fun `a point just inside the corner arc on the diagonal is inside and just outside is outside`() {
        // The arc centre is (halfW - corner, halfH - corner) = (170, 35) and its radius is 13.
        // A point on the diagonal at distance 12.5 from the arc centre is inside; at 13.5 it is outside.
        val arcX = halfW - corner
        val arcY = halfH - corner
        val inside = (corner - 0.5f) / sqrt(2f)
        val outside = (corner + 0.5f) / sqrt(2f)
        assertTrue("overlay: just inside the arc on the diagonal", CloseTargetShape.contains(arcX + inside, arcY + inside, half))
        assertFalse("overlay: just outside the arc on the diagonal", CloseTargetShape.contains(arcX + outside, arcY + outside, half))
    }

    @Test
    fun `a point exactly on the corner arc is inside and one step further out is outside`() {
        // At scale 1 the box has half width 366, half height 96, corner 26, and the arc centre is (340, 70).
        // (10, 24) lies on the arc because 10 * 10 + 24 * 24 is 676, the square of 26.
        assertTrue("overlay: a point exactly on the arc is inside", CloseTargetShape.contains(350f, 94f, 1f))
        assertTrue("overlay: the mirror point on the arc is inside", CloseTargetShape.contains(364f, 80f, 1f))
        assertFalse("overlay: one step further out along x is outside", CloseTargetShape.contains(351f, 94f, 1f))
    }

    @Test
    fun `a point in the margin just outside the drawn box is outside`() {
        // The drawn box ends at 183 on x; the canvas margin reaches 185, so 184 is in the margin.
        val marginX = (CloseTargetShape.ART_WIDTH / 2f) * half - 1f
        val marginY = (CloseTargetShape.ART_HEIGHT / 2f) * half - 1f
        assertFalse("overlay: x in the margin is outside the drawn box", CloseTargetShape.contains(marginX, 0f, half))
        assertFalse("overlay: y in the margin is outside the drawn box", CloseTargetShape.contains(0f, marginY, half))
    }

    @Test
    fun `points far above the box, where the old circle reached, are outside`() {
        assertFalse("overlay: a point far above the box is outside", CloseTargetShape.contains(0f, -150f, half))
        assertFalse("overlay: a point just past the right side of the box is outside", CloseTargetShape.contains(200f, 0f, half))
    }

    @Test
    fun `contains is false for a scale of zero`() {
        assertFalse("overlay: nothing is inside a box of scale 0, not even the centre", CloseTargetShape.contains(0f, 0f, 0f))
    }

    @Test
    fun `the box is symmetric in the four quadrants`() {
        val points = listOf(
            10f to 20f,
            100f to 40f,
            175f to 30f,
            180f to 47f,
            183f to 0f,
            150f to 46f,
            200f to 10f,
        )
        for ((x, y) in points) {
            val expected = CloseTargetShape.contains(x, y, half)
            assertEquals("overlay: the box is symmetric in x for ($x, $y)", expected, CloseTargetShape.contains(-x, y, half))
            assertEquals("overlay: the box is symmetric in y for ($x, $y)", expected, CloseTargetShape.contains(x, -y, half))
            assertEquals("overlay: the box is symmetric in both for ($x, $y)", expected, CloseTargetShape.contains(-x, -y, half))
        }
    }

    @Test
    fun `the drawn box is shown at the target centre in the dark palette for the light theme`() {
        val window = FakeTileWindow()
        CloseTarget.onDragStep(window, ThemeMode.LIGHT)
        assertShownDarkBox(window)
    }

    @Test
    fun `the drawn box is shown in the dark palette for the dark theme too`() {
        val window = FakeTileWindow()
        CloseTarget.onDragStep(window, ThemeMode.DARK)
        assertShownDarkBox(window)
    }

    /** Checks the one box call that the drag step made: centre, window size and the dark palette. */
    private fun assertShownDarkBox(window: FakeTileWindow) {
        val bounds = window.usableBounds()
        val (cx, cy) = CloseTarget.targetCentreInPixels(bounds)
        val shown = window.closeTargetShows.last()
        assertEquals("overlay: the box is shown at the target centre x", cx, shown.x)
        assertEquals("overlay: the box is shown at the target centre y", cy, shown.y)
        assertEquals("overlay: the box window width comes from CloseTargetShape", CloseTargetShape.windowWidthPx(bounds), shown.widthPx)
        assertEquals("overlay: the box window height comes from CloseTargetShape", CloseTargetShape.windowHeightPx(bounds), shown.heightPx)
        assertEquals("overlay: the box is drawn from the dark palette in both modes", TruckingTokens.DARK, shown.palette)
    }
}
