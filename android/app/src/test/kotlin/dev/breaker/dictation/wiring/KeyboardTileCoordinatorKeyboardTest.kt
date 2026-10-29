package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyboard-show/hide path tests for the coordinator, built on [TileRig] exactly as sketched: each test drives
 * [TileCoordinator.onKeyboardReading] through the rig and asserts the fake's own visibility and hide-call counts.
 * These pin the auto-pop show and the Hide reset of tileShown; they follow the established observable style in
 * KeyboardTileWiringTest without naming any runner type.
 */
class KeyboardTileCoordinatorKeyboardTest {

    private fun rig(arm: Boolean = true): TileRig = TileRig(arm = arm)

    /** A close while a keyboard session is live keeps the tile down until that session ends. */
    @Test
    fun `a close during the session stays closed for the rest of it`() {
        val r = rig()                     // armed at construction: tile already shown
        r.coordinator.onKeyboardReading(true, true, 1000L); r.settle()   // Show (idempotent: guard leaves the shown tile alone)
        r.coordinator.onCloseRequested(); r.settle()                    // close DURING the session: hide, session remembers it
        assertFalse("app: a close during the session hides the tile", r.tile.shown)
        r.coordinator.onKeyboardReading(true, false, 1500L); r.settle()  // focus lost with the keyboard up: NoOp
        r.coordinator.onKeyboardReading(true, true, 2000L); r.settle()   // focus back, same session: remembered close keeps it NoOp
        assertFalse("app: the remembered close keeps the tile down for the rest of the session", r.tile.shown)
    }

    /** A keyboard-gone reading after a keyboard show takes the tile off screen, with one hide call. */
    @Test
    fun `a keyboard hide takes the tile off screen`() {
        val r = rig(arm = false)          // the tile is taken down by a refused show, then the switch goes on
        r.tile.showResult = TileShow.NO_PERMISSION
        r.coordinator.onArmedChanged(true)
        r.tile.showResult = TileShow.SHOWN
        r.coordinator.onKeyboardReading(true, true, 1000L); r.settle()   // Show through the guard (tile was not shown)
        assertTrue("app: the keyboard show put the tile up", r.tile.shown)
        r.coordinator.onKeyboardReading(false, false, 1500L); r.settle()  // ime gone, session raised it: Hide
        assertFalse("app: a Hide must take the tile off screen", r.tile.shown)
        assertEquals("app: exactly one hide call for the session hide", 1, r.tile.calls.count { it == "hide" })
    }

    /** Two keyboard sessions each hide the tile at their end, and each hide is recorded. */
    @Test
    fun `a second hide after a hide takes the tile off screen and records each hide`() {
        val r = rig(arm = false)
        r.tile.showResult = TileShow.NO_PERMISSION
        r.coordinator.onArmedChanged(true)
        r.tile.showResult = TileShow.SHOWN
        r.coordinator.onKeyboardReading(true, true, 1000L); r.settle()    // Show (tile was not shown -> raised)
        r.coordinator.onKeyboardReading(false, false, 1500L); r.settle()  // Hide (session ends)
        r.coordinator.onKeyboardReading(true, true, 2000L); r.settle()    // new session: Show again (guard shows it back; this raise must be recorded)
        assertTrue("app: the new session brought the tile back", r.tile.shown)
        r.coordinator.onKeyboardReading(false, false, 2500L); r.settle()   // ime gone: this session raised it -> Hide again
        assertFalse("app: the second hide takes the tile off screen", r.tile.shown)
        assertEquals("app: two hide calls, one per session end", 2, r.tile.calls.count { it == "hide" })
    }
}
