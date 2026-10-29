package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A switch-off with the keyboard up releases the tile's raised mark. The keyboard tile hides itself on a
 * keyboard-gone reading only when the session raised the tile, so a mark that survives a switch-off would
 * take down a tile the user switched on again. The test counts the hide calls from the switch-on on, and the
 * later keyboard reading must add none. The rig and the fakes are the ones in TileFakes.kt.
 */
class KeyboardSwitchOffReleaseTest {

    private fun hides(r: TileRig): Int = r.tile.calls.count { it == "hide" }

    /** A rig whose tile was brought up by a keyboard show, so the session has raised it. */
    private fun raisedByKeyboard(): TileRig {
        val r = TileRig(arm = false)
        r.tile.showResult = TileShow.NO_PERMISSION
        r.coordinator.onArmedChanged(true)
        r.tile.showResult = TileShow.SHOWN
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertTrue("the keyboard show must have raised the tile", r.tile.shown)
        return r
    }

    @Test
    fun `a switch off with the keyboard up releases the raised mark so a later keyboard going hides nothing`() {
        val r = raisedByKeyboard()
        r.coordinator.onArmedChanged(false)
        r.settle()
        r.coordinator.onArmedChanged(true)
        r.settle()
        assertTrue("the switch on must show the tile", r.tile.shown)
        val afterSwitchOn: Int = hides(r)
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertTrue("the keyboard going must not hide the tile the user switched on", r.tile.shown)
        assertEquals("no hide call may follow the switch on", afterSwitchOn, hides(r))
    }
}
