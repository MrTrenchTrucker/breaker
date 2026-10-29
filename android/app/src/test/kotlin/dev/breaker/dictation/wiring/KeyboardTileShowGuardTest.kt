package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
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
    fun `a keyboard show after a close during a take shows the tile and begins no take`() {
        val r = TileRig()
        r.startRecording()
        r.coordinator.onCloseRequested()
        r.settle()
        assertEquals("the close must cancel the take once", 1, r.take.cancelCalls)
        r.coordinator.onKeyboardReading(true, true, 2000L)
        r.settle()
        assertTrue(
            "TileCoordinator.onKeyboardReading: a keyboard show after a close during a take must show the tile",
            r.tile.shown,
        )
        assertEquals("TileCoordinator.onKeyboardReading: a keyboard show must begin no take", 1, r.take.beginCalls)
        assertEquals(
            "TileCoordinator.onKeyboardReading: no take may record after the close",
            DictationState.IDLE,
            r.take.sessionState,
        )
    }

    @Test
    fun `a keyboard show after a close during a pending begin shows the tile and leaves no take recording`() {
        val r = TileRig()
        // No settle before the show: the begin stays queued, so the close and the show both happen before it runs.
        r.coordinator.onBegin()
        r.coordinator.onCloseRequested()
        r.coordinator.onKeyboardReading(true, true, 2000L)
        assertEquals("the begin must still be queued", 0, r.take.beginCalls)
        assertTrue(
            "TileCoordinator.onKeyboardReading: a keyboard show after a close must show the tile",
            r.tile.shown,
        )
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: only the tap's queued begin runs", 1, r.take.beginCalls)
        assertEquals(
            "TileCoordinator.onKeyboardReading: the close must cancel the pending begin once",
            1,
            r.take.cancelCalls,
        )
        assertEquals(
            "TileCoordinator.onKeyboardReading: no take may record after the close",
            DictationState.IDLE,
            r.take.sessionState,
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
