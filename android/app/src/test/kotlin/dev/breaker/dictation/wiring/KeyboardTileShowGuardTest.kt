package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A keyboard reading that puts the tile back on screen follows the same guard as a shake. It never
 * shows the tile while the switch is off, while a take is recording, or while a begin, a send, a
 * microphone yield or a device failure is still in progress. The keyboard Show branch is in
 * TileCoordinator.onKeyboardReading. Each test sets up one condition and names it in its message.
 */
class KeyboardTileShowGuardTest {

    private fun showCalls(r: TileRig): Int = r.tile.calls.count { it == "show" }

    @Test
    fun `a keyboard show with the switch off does not show the tile`() {
        val r = TileRig(arm = false)
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertEquals(
            "TileCoordinator.onKeyboardReading: a keyboard show with the switch off must make no show call",
            0,
            showCalls(r),
        )
        assertFalse(
            "TileCoordinator.onKeyboardReading: a keyboard show with the switch off must leave the tile down",
            r.tile.shown,
        )
    }

    @Test
    fun `a keyboard show during a take does not bring a closed tile back`() {
        val r = TileRig()
        r.startRecording()
        r.coordinator.onCloseRequested()
        r.settle()
        r.coordinator.onKeyboardReading(true, true, 2000L)
        r.settle()
        assertFalse(
            "TileCoordinator.onKeyboardReading: a keyboard show while the take records must not show a closed tile",
            r.tile.shown,
        )
    }

    @Test
    fun `a keyboard show while a begin is pending does not bring a closed tile back`() {
        val r = TileRig()
        // No settle here: the begin stays queued on the background, so only the pending begin can block the show.
        r.coordinator.onBegin()
        r.coordinator.onCloseRequested()
        r.coordinator.onKeyboardReading(true, true, 2000L)
        assertEquals("the begin must still be queued", 0, r.take.beginCalls)
        assertFalse(
            "TileCoordinator.onKeyboardReading: a keyboard show while a begin is pending must not show a closed tile",
            r.tile.shown,
        )
    }

    @Test
    fun `a keyboard show while a send is on its way does not bring a closed tile back`() {
        val r = TileRig()
        r.startRecording()
        // No settle after the send: its finish stays queued on the background, so only the send can block the show.
        r.coordinator.onSend()
        r.coordinator.onCloseRequested()
        r.coordinator.onKeyboardReading(true, true, 2000L)
        assertEquals("the send's finish must still be queued", 0, r.take.finishCalls)
        assertFalse(
            "TileCoordinator.onKeyboardReading: a keyboard show while a send is on its way must not show a closed tile",
            r.tile.shown,
        )
    }

    @Test
    fun `a keyboard show while the microphone yield runs does not bring a closed tile back`() {
        val r = TileRig()
        r.startRecording()
        // No settle after the cut: the yield's finish stays queued, so only the running yield can block the show.
        r.coordinator.onTakeMicTaken()
        r.coordinator.onCloseRequested()
        r.coordinator.onKeyboardReading(true, true, 2000L)
        assertEquals("the yield's finish must still be queued", 0, r.take.finishCalls)
        assertFalse(
            "TileCoordinator.onKeyboardReading: a keyboard show while the microphone yield runs must not show a closed tile",
            r.tile.shown,
        )
    }

    @Test
    fun `a keyboard show while a device failure runs does not bring a closed tile back`() {
        val r = TileRig()
        r.startRecording()
        // No settle after the failure: its finish stays queued on the background.
        assertTrue("the microphone failure must be taken by the tile", r.coordinator.onTakeFailed())
        r.coordinator.onCloseRequested()
        r.coordinator.onKeyboardReading(true, true, 2000L)
        assertEquals("the device failure's finish must still be queued", 0, r.take.finishCalls)
        assertFalse(
            "TileCoordinator.onKeyboardReading: a keyboard show while a device failure runs must not show a closed tile",
            r.tile.shown,
        )
    }

    @Test
    fun `a keyboard show that brings the tile up pushes the armed state`() {
        val r = TileRig(arm = false)
        r.tile.showResult = TileShow.NO_PERMISSION
        r.coordinator.onArmedChanged(true)
        r.tile.showResult = TileShow.SHOWN
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertTrue("TileCoordinator.onKeyboardReading: the keyboard show must put the tile up", r.tile.shown)
        assertEquals(
            "TileCoordinator.onKeyboardReading: after a keyboard show puts the tile up, the armed state must be pushed to it",
            listOf(TileState.ARMED),
            r.tile.states,
        )
    }

    @Test
    fun `a keyboard show after a user close brings the tile back and the next keyboard gone hides it`() {
        val r = TileRig()
        r.coordinator.onCloseRequested()
        r.settle()
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertTrue(
            "TileCoordinator.onCloseRequested: a keyboard show after a user close must bring the tile back",
            r.tile.shown,
        )
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertFalse(
            "TileCoordinator.onCloseRequested: the keyboard show must mark the session raised, so the keyboard gone hides the tile",
            r.tile.shown,
        )
    }
}
