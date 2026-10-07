package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dragging the tile: taps, moves, clamping, cancels and a changed display.
 *
 * The default screen is 16,80 to 1096,2300 with a 100 px tile, so the tile may sit at x 16..996 and y 80..2200.
 * A saved fraction of 0.5 on both axes puts it at 506,1140. Every expected pixel below is worked out by hand.
 */
class TileControllerDragTest {

    /** One controller over fresh fakes, with counters for the tap and failure callbacks. */
    private class Rig(seed: AppSettings = AppSettings()) {
        var taps = 0
        var saveFailures = 0
        val window = FakeTileWindow()
        val settings = FakeSettingsStore(seed)
        val controller = TestData.controller(
            window,
            settings,
            onTap = { taps += 1 },
            onSaveFailed = { saveFailures += 1 },
        )

        /** Shows the tile and checks it starts at the centre, 506,1140. */
        fun show() {
            assertEquals("android_overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            assertEquals("android_overlay: one window should be added by show", 1, window.adds.size)
            assertEquals("android_overlay: the tile should start at x 506", 506, window.adds[0].x)
            assertEquals("android_overlay: the tile should start at y 1140", 1140, window.adds[0].y)
        }

        /** A whole drag: down at 300,300, one move by the delta, release where the move ended. */
        fun dragBy(dx: Float, dy: Float) {
            window.down(300f, 300f)
            window.move(300f + dx, 300f + dy)
            window.up(300f + dx, 300f + dy)
        }
    }

    /** If this fails, a release inside the slop is not a single tap, or it moved or saved something. */
    @Test
    fun `a tap calls onTap once and moves and saves nothing`() {
        val rig = Rig()
        rig.show()

        rig.window.down(300f, 300f)
        rig.window.move(303f, 302f)
        rig.window.up(304f, 302f)

        assertEquals("android_overlay: a tap should call onTap exactly once", 1, rig.taps)
        assertTrue("android_overlay: a tap should not move the tile", rig.window.moves.isEmpty())
        assertEquals("android_overlay: a tap should not save", 0, rig.settings.saveCount)
        assertTrue("android_overlay: a tap should record no saved settings", rig.settings.saved.isEmpty())
        assertEquals("android_overlay: a tap should not report a save failure", 0, rig.saveFailures)
    }

    /** If this fails, the tile does not follow the finger by the reported delta, or a drag was counted as a tap. */
    @Test
    fun `a drag moves the tile by the finger delta and never taps`() {
        val rig = Rig()
        rig.show()

        rig.window.down(300f, 300f)
        rig.window.move(400f, 350f)
        rig.window.move(380f, 360f)
        rig.window.up(380f, 360f)

        assertEquals(
            "android_overlay: the moves should be 606,1190 (first delta 100,50 from the down point) then 586,1200 (delta -20,10)",
            listOf(PixelPoint(606, 1190), PixelPoint(586, 1200)),
            rig.window.moves,
        )
        assertEquals("android_overlay: a drag should never call onTap", 0, rig.taps)
    }

    /** If this fails, the tile can leave the usable area on at least one side, or the clamp is off by one pixel. */
    @Test
    fun `a drag is clamped on all four sides`() {
        // Each case drives the tile one pixel past an edge from 506,1140 and expects the edge itself.
        assertEquals("android_overlay: x 997 should clamp to 996", PixelPoint(996, 1140), lastMoveAfterDrag(491f, 0f))
        assertEquals("android_overlay: x 15 should clamp to 16", PixelPoint(16, 1140), lastMoveAfterDrag(-491f, 0f))
        assertEquals("android_overlay: y 2201 should clamp to 2200", PixelPoint(506, 2200), lastMoveAfterDrag(0f, 1061f))
        assertEquals("android_overlay: y 79 should clamp to 80", PixelPoint(506, 80), lastMoveAfterDrag(0f, -1061f))
    }

    private fun lastMoveAfterDrag(dx: Float, dy: Float): PixelPoint {
        val rig = Rig()
        rig.show()
        rig.dragBy(dx, dy)
        return rig.window.moves.last()
    }

    /** If this fails, the usable area is read once and kept, so the tile is clamped to a stale screen after a rotation. */
    @Test
    fun `the usable area is read again on every move`() {
        val rig = Rig()
        rig.show()
        rig.window.down(300f, 300f)
        rig.window.move(350f, 300f)
        assertEquals(
            "android_overlay: the first move should follow the finger to 556,1140",
            PixelPoint(556, 1140),
            rig.window.moves.last(),
        )

        // A smaller screen 0,0 to 500,1000 allows x 0..400 and y 0..900.
        rig.window.bounds = PixelBounds(0, 0, 500, 1000)
        val readsBefore = rig.window.boundsReads
        rig.window.move(450f, 300f)

        assertTrue("android_overlay: the usable area should be read again for the second move", rig.window.boundsReads > readsBefore)
        assertEquals(
            "android_overlay: the second move should clamp to the new screen at 400,900",
            PixelPoint(400, 900),
            rig.window.moves.last(),
        )
    }

    /** If this fails, a touch reaches the tile logic while no window is shown, or leaves state behind for the next show. */
    @Test
    fun `touches while the tile is hidden do nothing`() {
        val rig = Rig()

        // Never shown.
        rig.window.down(300f, 300f)
        rig.window.move(400f, 350f)
        rig.window.up(400f, 350f)
        rig.window.down(10f, 10f)
        rig.window.up(10f, 10f)
        rig.window.down(20f, 20f)
        rig.window.cancel()
        assertTrue("android_overlay: a never shown tile should not move", rig.window.moves.isEmpty())
        assertEquals("android_overlay: a never shown tile should not tap", 0, rig.taps)
        assertEquals("android_overlay: a never shown tile should not save", 0, rig.settings.saveCount)
        assertEquals("android_overlay: a never shown tile should not read settings", 0, rig.settings.loadCount)

        // Shown and hidden again.
        rig.show()
        rig.controller.hide()
        rig.window.down(300f, 300f)
        rig.window.move(400f, 350f)
        rig.window.up(400f, 350f)
        rig.window.down(10f, 10f)
        rig.window.up(10f, 10f)
        rig.window.cancel()
        assertTrue("android_overlay: a hidden tile should not move", rig.window.moves.isEmpty())
        assertEquals("android_overlay: a hidden tile should not tap", 0, rig.taps)
        assertEquals("android_overlay: a hidden tile should not save", 0, rig.settings.saveCount)

        // Control: once shown again the same sink does deliver a tap.
        assertEquals("android_overlay: the second show should report SHOWN", ShowResult.SHOWN, rig.controller.show())
        rig.window.down(10f, 10f)
        rig.window.up(10f, 10f)
        assertEquals("android_overlay: a shown tile should tap once", 1, rig.taps)
    }

    /** If this fails, a cancelled drag is lost, saved twice, or reported as a tap. */
    @Test
    fun `a cancel in a drag saves once and does not tap`() {
        val rig = Rig()
        rig.show()

        rig.window.down(300f, 300f)
        rig.window.move(400f, 350f)
        rig.window.cancel()

        assertEquals("android_overlay: the cancelled drag should have moved the tile once", listOf(PixelPoint(606, 1190)), rig.window.moves)
        assertEquals("android_overlay: a cancel in a drag should save exactly once", 1, rig.settings.saveCount)
        assertEquals("android_overlay: a cancel in a drag should record one saved settings", 1, rig.settings.saved.size)
        // 606,1190 is (606-16)/980 and (1190-80)/2120 of the movable range.
        assertEquals("android_overlay: saved x fraction should be 590/980", 0.6020408f, rig.settings.saved[0].tilePosition.x, 0.0001f)
        assertEquals("android_overlay: saved y fraction should be 1110/2120", 0.5235849f, rig.settings.saved[0].tilePosition.y, 0.0001f)
        assertEquals("android_overlay: a cancel in a drag should not call onTap", 0, rig.taps)
    }

    /** If this fails, a cancel that never became a drag tapped, moved or saved. */
    @Test
    fun `a cancel without a drag does nothing`() {
        val rig = Rig()
        rig.show()

        rig.window.cancel()
        rig.window.down(300f, 300f)
        rig.window.move(303f, 302f)
        rig.window.cancel()

        assertEquals("android_overlay: a cancel without a drag should not tap", 0, rig.taps)
        assertTrue("android_overlay: a cancel without a drag should not move the tile", rig.window.moves.isEmpty())
        assertEquals("android_overlay: a cancel without a drag should not save", 0, rig.settings.saveCount)
        assertEquals("android_overlay: a cancel without a drag should not report a failure", 0, rig.saveFailures)

        // Control: the next gesture starts clean and taps.
        rig.window.down(300f, 300f)
        rig.window.up(300f, 300f)
        assertEquals("android_overlay: a tap after the cancel should call onTap once", 1, rig.taps)
    }

    /** If this fails, a drag leaves the gesture state dirty so the next tap is lost or turns into a drag. */
    @Test
    fun `a tap after a finished drag still taps`() {
        val rig = Rig()
        rig.show()

        rig.dragBy(100f, 50f)
        assertEquals("android_overlay: the drag should not tap", 0, rig.taps)
        assertEquals("android_overlay: the drag should save once", 1, rig.settings.saveCount)

        rig.window.down(200f, 200f)
        rig.window.up(201f, 200f)

        assertEquals("android_overlay: the tap after the drag should call onTap once", 1, rig.taps)
        assertEquals("android_overlay: the tap should not move the tile again", listOf(PixelPoint(606, 1190)), rig.window.moves)
        assertEquals("android_overlay: the tap should not save again", 1, rig.settings.saveCount)
    }

    /** If this fails, a rotation leaves the tile at stale pixels, or a hidden tile reacts to a display change. */
    @Test
    fun `a display change recomputes the position from the saved fraction while shown and does nothing while hidden`() {
        // Shown at the centre: 0.5 on a screen 10,20 to 610,1020 (movable 500 by 900) is 10+250 and 20+450.
        val centred = Rig()
        centred.show()
        centred.window.bounds = PixelBounds(10, 20, 610, 1020)
        centred.controller.onDisplayChanged()
        assertEquals("android_overlay: one move from the display change", 1, centred.window.moves.size)
        assertEquals(
            "android_overlay: the centre fraction on the new screen should be 260,470",
            PixelPoint(260, 470),
            centred.window.moves.last(),
        )

        // After a drag the current fraction is 590/980 and 1110/2120: 10+301 and 20+471.
        val dragged = Rig()
        dragged.show()
        dragged.dragBy(100f, 50f)
        dragged.window.bounds = PixelBounds(10, 20, 610, 1020)
        dragged.controller.onDisplayChanged()
        assertEquals("android_overlay: the drag move plus the display change move", 2, dragged.window.moves.size)
        assertEquals(
            "android_overlay: the dragged fraction on the new screen should be 311,491",
            PixelPoint(311, 491),
            dragged.window.moves.last(),
        )

        // Hidden: nothing moves, before ever showing and after a hide.
        val never = Rig()
        never.window.bounds = PixelBounds(10, 20, 610, 1020)
        never.controller.onDisplayChanged()
        assertTrue("android_overlay: a never shown tile should not move on a display change", never.window.moves.isEmpty())

        val hidden = Rig()
        hidden.show()
        hidden.controller.hide()
        hidden.window.bounds = PixelBounds(10, 20, 610, 1020)
        hidden.controller.onDisplayChanged()
        assertTrue("android_overlay: a hidden tile should not move on a display change", hidden.window.moves.isEmpty())
    }

    /** If this fails, a display change in the middle of a drag moves the tile under the finger or corrupts the drag. */
    @Test
    fun `a display change in the middle of a drag is ignored`() {
        val rig = Rig()
        rig.show()
        rig.window.down(300f, 300f)
        rig.window.move(400f, 350f)
        assertEquals("android_overlay: the drag should have moved the tile once", 1, rig.window.moves.size)

        // A larger screen 0,0 to 2000,3000 allows x 0..1900 and y 0..2900; the centre there would be 950,1450.
        rig.window.bounds = PixelBounds(0, 0, 2000, 3000)
        rig.controller.onDisplayChanged()
        assertEquals("android_overlay: a display change during a drag should not move the tile", 1, rig.window.moves.size)

        // The drag carries on from 606,1190, not from the recomputed centre.
        rig.window.move(420f, 350f)
        assertEquals("android_overlay: the drag should have moved the tile twice", 2, rig.window.moves.size)
        assertEquals(
            "android_overlay: the next move should continue from 606,1190 to 626,1190",
            PixelPoint(626, 1190),
            rig.window.moves.last(),
        )
        rig.window.up(420f, 350f)
        assertEquals("android_overlay: the drag should save once", 1, rig.settings.saveCount)
        assertEquals("android_overlay: the drag should not tap", 0, rig.taps)
    }
}
