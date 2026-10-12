package dev.breaker.dictation.overlay
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry tests for the close target: where it sits, whether a tile centre is inside it,
 * and the highlight state. These tests run on a plain JVM and use no Android types.
 *
 * The default screen in [TestData] is 1080 by 2220 pixels starting at (16, 80). The target
 * centre is at (0.5, 0.87) of the usable bounds: (16 + 540, 80 + 1931.4) = (556, 2011.4).
 * The target radius is 0.15 * min(1080, 2220) = 0.15 * 1080 = 162 pixels.
 * The tile is 100 pixels square, so the tile centre is at (pixels.x + 50, pixels.y + 50).
 */
class CloseTargetTest {

    private val bounds = TestData.SCREEN

    /** If this fails, a tile centre at the very bottom edge of the usable area is not inside the target. */
    @Test
    fun `centre at the very bottom edge is outside`() {
        // The target centre is at y = 80 + 0.87 * 2220 = 2011.4. The bottom edge is at y = 2300.
        // A tile centre at y = 2300 is 289 pixels below the target centre, which is more than the
        // radius of 162 pixels, so it is outside.
        val cx = 556  // target centre x
        val cy = 2300  // bottom edge
        assertFalse(
            "overlay: a tile centre at the very bottom edge should be outside the target",
            CloseTarget.contains(cx, cy, bounds),
        )
    }

    /** If this fails, a tile centre just above the target is wrongly inside. */
    @Test
    fun `centre just above the target is outside`() {
        // The target centre is at y = 2011.4. A tile centre at y = 2011.4 - 163 = 1848.4, so cy = 1848
        // is 163.4 pixels above the target centre, which is more than the radius of 162 pixels, so it is outside.
        val cx = 556
        val cy = 1848
        assertFalse(
            "overlay: a tile centre just above the target should be outside",
            CloseTarget.contains(cx, cy, bounds),
        )
    }

    /** If this fails, the target is not centred horizontally. */
    @Test
    fun `horizontal centring`() {
        val (centerX, _) = CloseTarget.targetCentreFractions()
        assertEquals("overlay: the target should be centred horizontally", 0.5f, centerX, 0.001f)
    }

    /** If this fails, a tile centre at the target centre is not inside. */
    @Test
    fun `centre at the target centre is inside`() {
        val cx = 556
        val cy = 2011
        assertTrue(
            "overlay: a tile centre at the target centre should be inside",
            CloseTarget.contains(cx, cy, bounds),
        )
    }

    /** If this fails, a tile centre far from the target is wrongly inside. */
    @Test
    fun `centre far from the target is outside`() {
        val cx = 100
        val cy = 100
        assertFalse(
            "overlay: a tile centre far from the target should be outside",
            CloseTarget.contains(cx, cy, bounds),
        )
    }

    /** If this fails, the usable area smaller than the target diameter is not handled. */
    @Test
    fun `usable area smaller than target diameter`() {
        // A usable area of 100 by 100 pixels: the target radius is 0.15 * 100 = 15 pixels.
        // The target centre is at (50, 87). A tile centre at (50, 87) is inside.
        val smallBounds = PixelBounds(0, 0, 100, 100)
        val cx = 50
        val cy = 87
        assertTrue(
            "overlay: a tile centre at the target centre should be inside even on a small screen",
            CloseTarget.contains(cx, cy, smallBounds),
        )
        // A tile centre at (50, 72) is 15 pixels above the target centre, which is exactly the radius,
        // so it is inside (the edge is inside).
        assertTrue(
            "overlay: a tile centre on the target edge should be inside",
            CloseTarget.contains(50, 72, smallBounds),
        )
        // A tile centre at (50, 71) is 16 pixels above the target centre, which is outside.
        assertFalse(
            "overlay: a tile centre just outside the target edge should be outside",
            CloseTarget.contains(50, 71, smallBounds),
        )
    }

    /** If this fails, a tile centre exactly on the target edge is not inside. */
    @Test
    fun `centre exactly on the target edge is inside`() {
        // PixelBounds(0, 0, 1000, 2000): centre (500, 1740), radius 0.15 * 1000 = 150.
        // Point (650, 1740) is exactly 150 pixels right of centre, on the edge -> INSIDE.
        val exactBounds = PixelBounds(0, 0, 1000, 2000)
        assertTrue(
            "overlay: a tile centre exactly on the target edge should be inside",
            CloseTarget.contains(650, 1740, exactBounds),
        )
        // Point (651, 1740) is 151 pixels right of centre, 1 pixel outside -> OUTSIDE.
        assertFalse(
            "overlay: a tile centre 1 pixel outside the target edge should be outside",
            CloseTarget.contains(651, 1740, exactBounds),
        )
    }

    /** If this fails, the highlight state does not match the contains result. */
    @Test
    fun `highlight INSIDE when the centre is inside`() {
        val cx = 556
        val cy = 2011
        assertEquals(
            "overlay: the highlight should be INSIDE when the centre is inside the target",
            CloseHighlight.INSIDE,
            CloseTarget.highlight(cx, cy, bounds),
        )
    }

    /** If this fails, the highlight state does not match the contains result. */
    @Test
    fun `highlight OUTSIDE when the centre is outside`() {
        val cx = 100
        val cy = 100
        assertEquals(
            "overlay: the highlight should be OUTSIDE when the centre is outside the target",
            CloseHighlight.OUTSIDE,
            CloseTarget.highlight(cx, cy, bounds),
        )
    }

    /** If this fails, the highlight state does not match the contains result at the edge. */
    @Test
    fun `highlight INSIDE when the centre is on the edge`() {
        // The target centre is at y = 2011.4, radius = 162. The edge is at 2011.4 - 162 = 1849.4,
        // so cy = 1850 is 0.4 pixels inside the edge, and it is inside.
        val cx = 556
        val cy = 1850
        assertEquals(
            "overlay: the highlight should be INSIDE when the centre is on the target edge",
            CloseHighlight.INSIDE,
            CloseTarget.highlight(cx, cy, bounds),
        )
    }

    /** If this fails, the target radius fraction is not positive. */
    @Test
    fun `target radius fraction is positive`() {
        assertTrue(
            "overlay: the target radius fraction should be positive",
            CloseTarget.targetRadiusFraction() > 0f,
        )
    }

    /** If this fails, the target y fraction is not 0.87. */
    @Test
    fun `centre at the target y fraction 0_87`() {
        val (_, centerY) = CloseTarget.targetCentreFractions()
        assertEquals("overlay: the target y fraction should be 0.87", 0.87f, centerY, 0.001f)
    }

    /** If this fails, the target centre in pixels is not at the target fractions of the bounds. */
    @Test
    fun `target centre in pixels is at the target fractions of the bounds`() {
        val bounds = PixelBounds(0, 0, 1000, 2000)
        val (cx, cy) = CloseTarget.targetCentreInPixels(bounds)
        assertEquals("overlay: target centre x should be at 0.5 of bounds width", 500, cx)
        assertEquals("overlay: target centre y should be at 0.87 of bounds height", 1740, cy)
    }

    // --- drawn radius equals hit radius ---

    /** If this fails, the drawn radius no longer equals the hit radius. */
    @Test
    fun `the drawn radius equals the hit radius`() {
        val window = FakeTileWindow()
        CloseTarget.onDragStep(window, ThemeMode.LIGHT)
        val radius = window.closeTargetShows.last().third
        val hitRadius = CloseTarget.hitRadiusPx(window.usableBounds())
        assertEquals("If this fails, the drawn radius no longer equals the hit radius", hitRadius, radius, 0.01f)
    }

    /** If this fails, contains() uses a different radius than hitRadiusPx. */
    @Test
    fun `a drop at the hit radius is inside`() {
        val window = FakeTileWindow()
        val bounds = window.usableBounds()
        val (cx, cy) = CloseTarget.targetCentreInPixels(bounds)
        val hitRadius = CloseTarget.hitRadiusPx(bounds)
        assertTrue(
            "If this fails, contains() uses a different radius than hitRadiusPx",
            CloseTarget.contains(cx + hitRadius.toInt(), cy, bounds),
        )
        assertFalse(
            "If this fails, contains() uses a different radius than hitRadiusPx",
            CloseTarget.contains(cx + hitRadius.toInt() + 1, cy, bounds),
        )
    }

    // --- non-collapsed drop hides the target ---

    /** If this fails, the non-collapsed drop no longer hides the target. */
    @Test
    fun `a drop on a non-collapsed shape hides the target`() {
        val window = FakeTileWindow()
        CloseTarget.onDragStep(window, ThemeMode.LIGHT)
        var closeRequested = false
        var saved = false
        CloseTarget.onDragEnd(window, false, 0, 0, { closeRequested = true }, {}, { saved = true })
        assertTrue("If this fails, the non-collapsed drop no longer hides the target", window.closeTargetHideCount > 0)
        assertFalse("If this fails, a non-collapsed drop must not close the tile", closeRequested)
        assertFalse("If this fails, a non-collapsed drop must not persist", saved)
    }

    // --- drop turns off the busy pulse ---

    /** If this fails, a drop on the target no longer turns off the busy pulse. */
    @Test
    fun `a drop on the target turns off the busy pulse`() {
        val window = FakeTileWindow()
        CloseTarget.onDragStep(window, ThemeMode.LIGHT)
        val (cx, cy) = CloseTarget.targetCentreInPixels(window.usableBounds())
        val tileX = cx - window.tileSizePx() / 2
        val tileY = cy - window.tileSizePx() / 2
        CloseTarget.onDragEnd(window, true, tileX, tileY, {}, {}, {})
        assertTrue(
            "If this fails, a drop on the target no longer turns off the busy pulse",
            window.busyPulseCalls.last() == false,
        )
    }

    // --- drop turns off the pulse ---

    /** If this fails, a drop on the target no longer turns off the pulse. */
    @Test
    fun `a drop on the target turns off the pulse`() {
        val window = FakeTileWindow()
        CloseTarget.onDragStep(window, ThemeMode.LIGHT)
        val (cx, cy) = CloseTarget.targetCentreInPixels(window.usableBounds())
        val tileX = cx - window.tileSizePx() / 2
        val tileY = cy - window.tileSizePx() / 2
        CloseTarget.onDragEnd(window, true, tileX, tileY, {}, {}, {})
        assertTrue(
            "If this fails, a drop on the target no longer turns off the pulse",
            window.pulseCalls.last() == false,
        )
    }

    // --- frameFor geometry ---

    /** If this failed, the circle is not centred on x. */
    @Test
    fun `frameFor centres the circle on the touch point`() {
        val (left, top, diameter) = CloseTargetWindow.frameFor(500, 900, 54f)
        assertEquals("If this failed, the circle is not centred on x", 446, left)
        assertEquals("If this failed, the circle is not centred on y", 846, top)
        assertEquals("If this failed, the diameter is not twice the radius", 108, diameter)
    }

    /** If this failed, the circle is not centred on x. */
    @Test
    fun `frameFor works for a second centre`() {
        val (left, top, diameter) = CloseTargetWindow.frameFor(300, 700, 40f)
        assertEquals("If this failed, the circle is not centred on x", 260, left)
        assertEquals("If this failed, the circle is not centred on y", 660, top)
        assertEquals("If this failed, the diameter is not twice the radius", 80, diameter)
    }

}
