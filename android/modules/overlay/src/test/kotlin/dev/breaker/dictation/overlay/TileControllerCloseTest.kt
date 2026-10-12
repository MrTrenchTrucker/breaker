package dev.breaker.dictation.overlay
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controller integration tests for the close target: a drag of the collapsed tile that ends on
 * the target calls [onCloseRequested] and hides the tile without saving the position; a drag that
 * ends elsewhere saves as today; a tap never closes.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100
 * pixels square. The target centre is at (556, 2011) with a radius of 162 pixels. The tile centre
 * during a drag is at (pixels.x + 50, pixels.y + 50).
 */
class TileControllerCloseTest {

    private class Rig(
        seed: AppSettings = AppSettings(),
        onCloseRequested: () -> Unit = {},
    ) {
        val log = ArrayList<String>()
        val window = FakeTileWindow()
        val store = FakeSettingsStore(seed)
        val controller = TileController(
            window, store,
            onTap = { log.add("tap") },
            theme = ThemeMode.LIGHT,
            slopPx = TestData.SLOP_PX,
            onCloseRequested = { log.add("close"); onCloseRequested() },
        )

        /** Show the tile and forget the calls made so far. */
        fun shown(): Rig {
            assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            forget()
            return this
        }

        fun forget() {
            window.calls.clear()
            window.frames.clear()
            window.appliedFaces.clear()
            window.moves.clear()
            window.closeTargetShows.clear()
            window.closeTargetHideCount = 0
            window.closeTargetHighlights.clear()
            log.clear()
        }

        /** Drag the tile by (dx, dy) pixels from its current position. */
        fun dragBy(dx: Int, dy: Int) {
            val startX = 556f  // tile centre x
            val startY = 1190f  // tile centre y
            window.down(startX, startY)
            // Move past the slop to start the drag
            window.move(startX + TestData.SLOP_PX + 1, startY)
            // Move to the target position
            val targetX = startX + dx
            val targetY = startY + dy
            window.move(targetX, targetY)
            window.up(targetX, targetY)
        }

        /** Drag the tile so its centre lands at (cx, cy). */
        fun dragToCentre(cx: Int, cy: Int) {
            val startX = 556f
            val startY = 1190f
            window.down(startX, startY)
            window.move(startX + TestData.SLOP_PX + 1, startY)
            window.move(cx.toFloat(), cy.toFloat())
            window.up(cx.toFloat(), cy.toFloat())
        }

        /** Tap at (x, y). */
        fun tap(x: Float, y: Float) {
            window.down(x, y)
            window.up(x, y)
        }
    }

    /** If this fails, a drag to the target does not call onCloseRequested. */
    @Test
    fun `drag to target calls onCloseRequested once`() {
        var count = 0
        val rig = Rig(onCloseRequested = { count++ }).shown()
        // Drag the tile centre to the target centre (556, 2011)
        rig.dragToCentre(556, 2011)
        assertEquals("overlay: onCloseRequested should be called once", 1, count)
        assertEquals("overlay: the callback should log once", listOf("close"), rig.log)
    }

    /** If this fails, a drag to the target does not hide the tile. */
    @Test
    fun `drag to target hides the tile`() {
        val rig = Rig().shown()
        rig.dragToCentre(556, 2011)
        assertFalse("overlay: the tile should be hidden after a drop on the target", rig.controller.isShown)
        assertTrue("overlay: the window should have been removed", rig.window.removeCount > 0)
    }

    /** If this fails, a drag to the target saves the dragged-to position. */
    @Test
    fun `drag to target does not save the position`() {
        val rig = Rig().shown()
        rig.dragToCentre(556, 2011)
        assertEquals("overlay: a drop on the target should not save the position", 0, rig.store.saveCount)
    }

    /** If this fails, a drag to a non-target position calls onCloseRequested. */
    @Test
    fun `drag to non-target does not call onCloseRequested`() {
        var count = 0
        val rig = Rig(onCloseRequested = { count++ }).shown()
        // Drag the tile centre to (556, 1000), which is far from the target
        rig.dragToCentre(556, 1000)
        assertEquals("overlay: onCloseRequested should not be called for a non-target drop", 0, count)
        assertTrue("overlay: the callback should not log a close", rig.log.none { it == "close" })
    }

    /** If this fails, a drag to a non-target position does not save. */
    @Test
    fun `drag to non-target saves the position`() {
        val rig = Rig().shown()
        rig.dragToCentre(556, 1000)
        assertEquals("overlay: a non-target drop should save the position", 1, rig.store.saveCount)
    }

    /** If this fails, a tap calls onCloseRequested. */
    @Test
    fun `tap does not call onCloseRequested`() {
        var count = 0
        val rig = Rig(onCloseRequested = { count++ }).shown()
        rig.tap(556f, 1190f)
        assertEquals("overlay: a tap should not call onCloseRequested", 0, count)
        assertTrue("overlay: the callback should not log a close", rig.log.none { it == "close" })
    }

    /** If this fails, a tap does not hide the tile. */
    @Test
    fun `tap does not hide the tile`() {
        val rig = Rig().shown()
        rig.tap(556f, 1190f)
        assertTrue("overlay: a tap should not hide the tile", rig.controller.isShown)
    }

    /** If this fails, a drag on the wide window calls onCloseRequested. */
    @Test
    fun `wide window drag does not call onCloseRequested`() {
        var count = 0
        val rig = Rig(onCloseRequested = { count++ }).shown()
        // Push a notice to make the tile wide
        rig.controller.showNotice("Turn dictation on first")
        rig.forget()
        // The wide window cannot be dragged, but we simulate a drag attempt
        // The gesture shape is NOTICE, so the drag should not trigger the close target
        val startX = 556f
        val startY = 1190f
        rig.window.down(startX, startY)
        rig.window.move(startX + TestData.SLOP_PX + 1, startY)
        rig.window.move(startX + 100, startY + 100)
        rig.window.up(startX + 100, startY + 100)
        assertEquals("overlay: a wide window drag should not call onCloseRequested", 0, count)
    }

    /** If this fails, a MIC_BUSY collapsed drag to the target does not call onCloseRequested. */
    @Test
    fun `MIC_BUSY collapsed drag to target calls onCloseRequested`() {
        var count = 0
        val rig = Rig(onCloseRequested = { count++ }).shown()
        rig.controller.setState(TileState.MIC_BUSY)
        rig.forget()
        rig.dragToCentre(556, 2011)
        assertEquals("overlay: a MIC_BUSY collapsed drag to the target should call onCloseRequested", 1, count)
    }

    /** If this fails, ACTION_CANCEL calls onCloseRequested. */
    @Test
    fun `ACTION_CANCEL does not call onCloseRequested`() {
        var count = 0
        val rig = Rig(onCloseRequested = { count++ }).shown()
        // Start a drag
        val startX = 556f
        val startY = 1190f
        rig.window.down(startX, startY)
        rig.window.move(startX + TestData.SLOP_PX + 1, startY)
        // Cancel the gesture
        rig.window.cancel()
        assertEquals("overlay: ACTION_CANCEL should not call onCloseRequested", 0, count)
        assertTrue("overlay: the callback should not log a close", rig.log.none { it == "close" })
    }

    /** If this fails, ACTION_CANCEL does not hide the tile. */
    @Test
    fun `ACTION_CANCEL does not hide the tile`() {
        val rig = Rig().shown()
        val startX = 556f
        val startY = 1190f
        rig.window.down(startX, startY)
        rig.window.move(startX + TestData.SLOP_PX + 1, startY)
        rig.window.cancel()
        assertTrue("overlay: ACTION_CANCEL should not hide the tile", rig.controller.isShown)
    }

    /** If this fails, a null onCloseRequested crashes the close gesture. */
    @Test
    fun `null onCloseRequested is safe`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TileController(
            window, store,
            onTap = {},
            theme = ThemeMode.LIGHT,
            slopPx = TestData.SLOP_PX,
            onCloseRequested = null,
        )
        assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
        // Drag to the target
        val startX = 556f
        val startY = 1190f
        window.down(startX, startY)
        window.move(startX + TestData.SLOP_PX + 1, startY)
        window.move(556f, 2011f)
        window.up(556f, 2011f)
        // The tile should be hidden (the close gesture still hides, just without the callback)
        assertFalse("overlay: the tile should be hidden after a drop on the target", controller.isShown)
    }

    /** If this fails, the pre-drag position is not preserved after a hide. */
    @Test
    fun `pre-drag position is preserved after hide`() {
        val rig = Rig().shown()
        // The tile starts at (506, 1140) with fraction (0.5, 0.5)
        // Drag to the target
        rig.dragToCentre(556, 2011)
        // The saved position should still be (0.5, 0.5), not the dragged-to position
        val saved = rig.store.saved
        assertTrue("overlay: no position should be saved on a close", saved.isEmpty())
        // The fraction in the controller should still be the pre-drag fraction
        // We can verify this by showing the tile again and checking it starts at the same place
        rig.forget()
        assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, rig.controller.show())
        val add = rig.window.adds.last()
        assertEquals("overlay: the tile should start at the pre-drag x position", 506, add.x)
        assertEquals("overlay: the tile should start at the pre-drag y position", 1140, add.y)
    }

    /** If this fails, the close target is not shown during a drag. */
    @Test
    fun `close target is shown during a drag`() {
        val rig = Rig().shown()
        val startX = 556f
        val startY = 1190f
        rig.window.down(startX, startY)
        rig.window.move(startX + TestData.SLOP_PX + 1, startY)
        // The close target should have been shown
        assertTrue("overlay: the close target should be shown during a drag", rig.window.closeTargetShows.isNotEmpty())
    }

    /** If this fails, the close target is not hidden after a drag ends. */
    @Test
    fun `close target is hidden after a drag ends`() {
        val rig = Rig().shown()
        rig.dragToCentre(556, 1000)
        assertTrue("overlay: the close target should be hidden after a drag ends", rig.window.closeTargetHideCount > 0)
    }

    /** If this fails, hide() does not hide the close target. */
    @Test
    fun `hide hides the close target`() {
        val rig = Rig().shown()
        // Start a drag so the target is shown
        val startX = 556f
        val startY = 1190f
        rig.window.down(startX, startY)
        rig.window.move(startX + TestData.SLOP_PX + 1, startY)
        assertTrue("overlay: the close target should be shown during a drag", rig.window.closeTargetShows.isNotEmpty())
        // Hide the tile
        rig.controller.hide()
        assertTrue("overlay: hide() should hide the close target", rig.window.closeTargetHideCount > 0)
    }

    /** If this fails, a touch cancel does not hide the close target. */
    @Test
    fun `ACTION_CANCEL hides the close target`() {
        var closeCount = 0
        val rig = Rig(onCloseRequested = { closeCount++ }).shown()
        // Start a drag so the target is shown
        val startX = 556f
        val startY = 1190f
        rig.window.down(startX, startY)
        rig.window.move(startX + TestData.SLOP_PX + 1, startY)
        assertTrue("overlay: the close target should be shown during a drag", rig.window.closeTargetShows.isNotEmpty())
        rig.forget()
        // Cancel the gesture
        rig.window.cancel()
        assertTrue("overlay: ACTION_CANCEL should hide the close target", rig.window.closeTargetHideCount > 0)
        assertEquals("overlay: ACTION_CANCEL should not call onCloseRequested", 0, closeCount)
        assertTrue("overlay: ACTION_CANCEL should not hide the tile", rig.controller.isShown)
        // The drag ended, so the position should have been saved
        assertEquals("overlay: ACTION_CANCEL should save the position", 1, rig.store.saveCount)
    }

    /** If this fails, the close target highlight is not updated during a drag. */
    @Test
    fun `close target highlight is updated during a drag`() {
        val rig = Rig().shown()
        val startX = 556f
        val startY = 1190f
        rig.window.down(startX, startY)
        rig.window.move(startX + TestData.SLOP_PX + 1, startY)
        // The highlight should have been set
        assertTrue("overlay: the close target highlight should be updated during a drag", rig.window.closeTargetHighlights.isNotEmpty())
    }

    /** If this fails, the close target highlight is INSIDE when the tile is over the target. */
    @Test
    fun `close target highlight is INSIDE when the tile is over the target`() {
        val rig = Rig().shown()
        // Drag the tile centre to the target centre
        rig.dragToCentre(556, 2011)
        // The last highlight should be INSIDE
        assertEquals(
            "overlay: the close target highlight should be INSIDE when the tile is over the target",
            CloseHighlight.INSIDE,
            rig.window.closeTargetHighlights.last(),
        )
    }

    /** If this fails, the close target highlight is OUTSIDE when the tile is away from the target. */
    @Test
    fun `close target highlight is OUTSIDE when the tile is away from the target`() {
        val rig = Rig().shown()
        // Drag the tile centre to a position far from the target
        rig.dragToCentre(556, 1000)
        // The last highlight should be OUTSIDE
        assertEquals(
            "overlay: the close target highlight should be OUTSIDE when the tile is away from the target",
            CloseHighlight.OUTSIDE,
            rig.window.closeTargetHighlights.last(),
        )
    }

    /** If this fails, the first drag step does not show the target at the target centre. */
    @Test
    fun `first drag step shows the target at the target centre, not the tile centre`() {
        val rig = Rig().shown()
        rig.window.closeTargetShows.clear()
        // Drag from a position far from the target centre
        rig.window.down(100f, 100f)
        rig.window.move(150f, 150f)
        rig.window.up(150f, 150f)
        assertTrue("overlay: close target should be shown", rig.window.closeTargetShows.isNotEmpty())
        val (showX, showY, _) = rig.window.closeTargetShows.first()
        val (targetCx, targetCy) = CloseTarget.targetCentreInPixels(rig.window.usableBounds())
        assertEquals("overlay: close target x should be at target centre", targetCx, showX)
        assertEquals("overlay: close target y should be at target centre", targetCy, showY)
    }
}
