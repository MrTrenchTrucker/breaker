package dev.breaker.dictation.overlay
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure geometry tests for the close target: where it sits, whether a tile centre is inside its
 * drawn box, and the highlight state. These tests run on a plain JVM and use no Android types.
 *
 * The default screen in [TestData] is 1080 by 2220 pixels starting at (16, 80). The target
 * centre is at (0.5, 0.905) of the usable bounds: (16 + 540, 80 + 2009.1) = (556, 2089.1).
 * The target window is 507 by 130 pixels, so its scale is 0.65 and the drawn box has half
 * width 237.9 and half height 62.4 pixels (see [CloseTargetShape]).
 * The tile is 100 pixels square, so the tile centre is at (pixels.x + 50, pixels.y + 50).
 */
class CloseTargetTest {

    private val bounds = TestData.SCREEN

    /** If this fails, a tile centre at the very bottom edge of the usable area is not inside the target. */
    @Test
    fun `centre at the very bottom edge is outside`() {
        // The target centre is at y = 80 + 0.905 * 2220 = 2089 (was 2011). The bottom edge is at y = 2300.
        // A tile centre at y = 2300 is ~211 pixels below the new target centre (~2089), which is more than the
        // half height of 62.4 pixels of the box, so it is outside.
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
        // The target centre at y = 80 + 0.905 * 2220 = 2089.1 (was 2011). The box top is 62.4 pixels
        // below it at y = 2027. The new fractions move the target down, so both sample points are now
        // above the box and outside the drawn area rather than just inside the top edge.
        // cy = 1948 is below the new box top (outside), so it is outside.
        // cy = 1949 is below the new box top (outside), so it is outside.
        val cx = 556
        assertFalse(
            "overlay: a tile centre one pixel above the top edge should be outside",
            CloseTarget.contains(cx, 1948, bounds),
        )
        assertFalse(
            "overlay: a tile centre just below the new top edge should be outside",
            CloseTarget.contains(cx, 1949, bounds),
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
        val cy = 2089
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
        // A usable area of 100 by 100 pixels: the window is 47 by 5 pixels, so the scale is 0.025 and
        // the drawn box has half height 2.4 pixels. The target centre is at (50, 90). A tile centre at
        // (50, 90) is inside.
        val smallBounds = PixelBounds(0, 0, 100, 100)
        val cx = 50
        val cy = 90
        assertTrue(
            "overlay: a tile centre at the target centre should be inside even on a small screen",
            CloseTarget.contains(cx, cy, smallBounds),
        )
        // A tile centre 2 pixels above the target centre is inside the 2.4 pixel half height.
        assertTrue(
            "overlay: a tile centre 2 pixels above the centre should be inside the small box",
            CloseTarget.contains(50, 88, smallBounds),
        )
        // A tile centre 3 pixels above the target centre is outside.
        assertFalse(
            "overlay: a tile centre 3 pixels above the centre should be outside the small box",
            CloseTarget.contains(50, 87, smallBounds),
        )
    }

    /** If this fails, the drawn right edge of the box is not inside, or one pixel past it is inside. */
    @Test
    fun `centre on the drawn right edge is inside and one pixel further is outside`() {
        // PixelBounds(0, 0, 1000, 2000): centre (500, 1810). The reach is the whole-pixel part of the
        // drawn half width, so the centre at the reach is inside and one pixel further is outside.
        val exactBounds = PixelBounds(0, 0, 1000, 2000)
        val reach = CloseTargetShape.halfWidthPx(CloseTarget.boxScale(exactBounds)).toInt()
        assertTrue(
            "overlay: a tile centre on the drawn right edge should be inside",
            CloseTarget.contains(500 + reach, 1810, exactBounds),
        )
        assertFalse(
            "overlay: a tile centre one pixel past the drawn right edge should be outside",
            CloseTarget.contains(500 + reach + 1, 1810, exactBounds),
        )
    }

    /** If this fails, the highlight state does not match the contains result. */
    @Test
    fun `highlight INSIDE when the centre is inside`() {
        val cx = 556
        val cy = 2089
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

    /** If this fails, the highlight state does not match the contains result on the top edge of the box. */
    @Test
    fun `highlight INSIDE when the centre is on the top edge`() {
        // The box half height on this screen is 62.4 pixels, so the centre 62 pixels above is inside.
        val reach = CloseTargetShape.halfHeightPx(CloseTarget.boxScale(bounds)).toInt()
        assertEquals(
            "overlay: the highlight should be INSIDE when the centre is on the top edge of the box",
            CloseHighlight.INSIDE,
            CloseTarget.highlight(556, 2089 - reach, bounds),
        )
    }

    /** If this fails, the box scale on the default screen is not positive. */
    @Test
    fun `box scale is positive on the default screen`() {
        assertTrue(
            "overlay: the box scale on the default screen should be positive",
            CloseTarget.boxScale(bounds) > 0f,
        )
    }

    /** If this fails, the target y fraction is not 0.905. */
    @Test
    fun `centre at the target y fraction 0_905`() {
        val (_, centerY) = CloseTarget.targetCentreFractions()
        assertEquals("overlay: the target y fraction should be 0.905", 0.905f, centerY, 0.001f)
    }

    /** If this fails, the target centre in pixels is not at the target fractions of the bounds. */
    @Test
    fun `target centre in pixels is at the target fractions of the bounds`() {
        val bounds = PixelBounds(0, 0, 1000, 2000)
        val (cx, cy) = CloseTarget.targetCentreInPixels(bounds)
        assertEquals("overlay: target centre x should be at 0.5 of bounds width", 500, cx)
        assertEquals("overlay: target centre y should be at 0.905 of bounds height", 1810, cy)
    }

    // --- drawn box equals drop area ---

    /** If this fails, the drawn box no longer has the window size that the drop area is tested with. */
    @Test
    fun `the drawn box equals the drop area`() {
        val window = FakeTileWindow()
        CloseTarget.onDragStep(window, ThemeMode.LIGHT)
        val shown = window.closeTargetShows.last()
        assertEquals("overlay: the drawn box width is the target window width", CloseTargetShape.windowWidthPx(window.usableBounds()), shown.widthPx)
        assertEquals("overlay: the drawn box height is the target window height", CloseTargetShape.windowHeightPx(window.usableBounds()), shown.heightPx)
        assertEquals("overlay: the drop area uses the scale of the drawn box", CloseTarget.boxScale(window.usableBounds()), CloseTargetShape.scale(shown.widthPx, shown.heightPx), 0f)
    }

    /** If this fails, a centre on the drawn bottom edge is not inside, or one pixel past it is inside. */
    @Test
    fun `centre on the drawn bottom edge is inside and one pixel further is outside`() {
        val window = FakeTileWindow()
        val (cx, cy) = CloseTarget.targetCentreInPixels(window.usableBounds())
        val reach = CloseTargetShape.halfHeightPx(CloseTarget.boxScale(window.usableBounds())).toInt()
        assertTrue(
            "overlay: a tile centre on the drawn bottom edge should be inside",
            CloseTarget.contains(cx, cy + reach, window.usableBounds()),
        )
        assertFalse(
            "overlay: a tile centre one pixel past the drawn bottom edge should be outside",
            CloseTarget.contains(cx, cy + reach + 1, window.usableBounds()),
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

    /** If this fails, the window is not centred on the touch point. */
    @Test
    fun `frameFor centres the window on the touch point`() {
        val frame = CloseTargetWindow.frameFor(500, 900, 300, 60)
        assertEquals("overlay: the window is centred on the touch point", CloseFrame(350, 870, 300, 60), frame)
    }

    /** If this fails, the window is not placed from the width and height it is given. */
    @Test
    fun `frameFor works for a second centre`() {
        val frame = CloseTargetWindow.frameFor(300, 700, 80, 40)
        assertEquals("overlay: the window is centred and sized from its own width and height", CloseFrame(260, 680, 80, 40), frame)
    }

}
