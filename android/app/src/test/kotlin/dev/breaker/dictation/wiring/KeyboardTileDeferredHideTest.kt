package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A keyboard-gone reading while a take is in flight must not hide the tile at once. The hide is held
 * and applied once, when the take leaves the in-flight states, and only if the keyboard is still gone.
 * A keyboard show clears the held hide. Each end path of a take is tested on its own, and the
 * keyboard-back cases must not hide. The hook is in TileCoordinator.push.
 */
class KeyboardTileDeferredHideTest {

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
    fun `a keyboard gone reading while a take records keeps the tile up`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertTrue(
            "TileCoordinator.onKeyboardReading: a keyboard-gone reading during a take must keep the tile up until the take ends",
            r.tile.shown,
        )
        assertEquals("TileCoordinator.onKeyboardReading: no hide call while the take records", 0, hides(r))
    }

    @Test
    fun `a take that ends by itself with the keyboard still gone hides the tile once`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: no hide call before the take ends", 0, hides(r))
        r.take.sessionState = DictationState.IDLE
        r.coordinator.onTakeEnded()
        r.settle()
        assertEquals(
            "TileCoordinator.push: exactly one hide when the take leaves the in-flight states with the keyboard gone",
            1,
            hides(r),
        )
        assertFalse("TileCoordinator.push: the tile must be down after that one hide", r.tile.shown)
    }

    @Test
    fun `a take that ends by itself with the keyboard back keeps the tile up`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertTrue("TileCoordinator.onKeyboardReading: the tile must stay up during the take", r.tile.shown)
        r.coordinator.onKeyboardReading(true, true, 3000L)
        r.settle()
        r.take.sessionState = DictationState.IDLE
        r.coordinator.onTakeEnded()
        r.settle()
        assertTrue(
            "TileCoordinator.push: a take that ends with the keyboard back must keep the tile up",
            r.tile.shown,
        )
        assertEquals("TileCoordinator.push: no hide call when the keyboard is back at take end", 0, hides(r))
    }

    @Test
    fun `a send that settles with the keyboard still gone hides the tile once`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: no hide call before the send settles", 0, hides(r))
        r.coordinator.onSend()
        r.settle()
        assertEquals(
            "TileCoordinator.push: exactly one hide when the send settles with the keyboard gone",
            1,
            hides(r),
        )
        assertFalse("TileCoordinator.push: the tile must be down after the send settles", r.tile.shown)
    }

    @Test
    fun `a send that settles with the keyboard back keeps the tile up`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: no hide call before the send settles", 0, hides(r))
        r.coordinator.onKeyboardReading(true, true, 3000L)
        r.settle()
        r.coordinator.onSend()
        r.settle()
        assertTrue(
            "TileCoordinator.push: a send that settles with the keyboard back must keep the tile up",
            r.tile.shown,
        )
        assertEquals("TileCoordinator.push: no hide call when the keyboard is back at send settle", 0, hides(r))
    }

    @Test
    fun `a cancel with the keyboard still gone hides the tile once`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: no hide call before the cancel", 0, hides(r))
        r.coordinator.onCancel()
        r.settle()
        assertEquals(
            "TileCoordinator.push: exactly one hide when the cancel leaves the in-flight states with the keyboard gone",
            1,
            hides(r),
        )
        assertFalse("TileCoordinator.push: the tile must be down after the cancel", r.tile.shown)
    }

    @Test
    fun `a microphone yield that settles with the keyboard still gone hides the tile once`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: no hide call before the yield settles", 0, hides(r))
        r.coordinator.onTakeMicTaken()
        r.settle()
        assertEquals(
            "TileCoordinator.push: exactly one hide when the microphone yield settles with the keyboard gone",
            1,
            hides(r),
        )
        assertFalse("TileCoordinator.push: the tile must be down after the yield settles", r.tile.shown)
    }

    @Test
    fun `a device failure that settles with the keyboard still gone hides the tile once`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: no hide call before the device failure settles", 0, hides(r))
        assertTrue("TileCoordinator.onTakeFailed: the microphone failure must be taken", r.coordinator.onTakeFailed())
        r.settle()
        assertEquals(
            "TileCoordinator.push: exactly one hide when the device failure settles with the keyboard gone",
            1,
            hides(r),
        )
        assertFalse("TileCoordinator.push: the tile must be down after the device failure settles", r.tile.shown)
    }

    @Test
    fun `a keyboard show during a take clears the held hide so a later take end does not hide`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: no hide call while the take records", 0, hides(r))
        r.coordinator.onKeyboardReading(true, true, 3000L)
        r.settle()
        r.take.sessionState = DictationState.IDLE
        r.coordinator.onTakeEnded()
        r.settle()
        assertEquals("TileCoordinator.onKeyboardReading: the keyboard show must clear the held hide", 0, hides(r))
        assertTrue("TileCoordinator.onKeyboardReading: the tile must stay up after the first take", r.tile.shown)
        // The second take must begin from the tile that is still on screen; no hide may come back at its end.
        r.startRecording()
        assertEquals("TileCoordinator.onBegin: the second take must begin once the tile is still up", 2, r.take.beginCalls)
        r.take.sessionState = DictationState.IDLE
        r.coordinator.onTakeEnded()
        r.settle()
        assertEquals("TileCoordinator.push: a later take end must not apply the cleared hide", 0, hides(r))
    }

    @Test
    fun `a keyboard show swallowed by the debounce window does not release a held hide`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertTrue(
            "TileCoordinator.onKeyboardReading: a keyboard-gone reading during a take must hold the hide, so the tile stays up",
            r.tile.shown,
        )
        // Inside the debounce window the session swallows this show, so no Show reaches the coordinator.
        r.coordinator.onKeyboardReading(true, true, 2100L)
        r.settle()
        r.take.sessionState = DictationState.IDLE
        r.coordinator.onTakeEnded()
        r.settle()
        assertEquals(
            "KeyboardHideHold: a held hide must not be applied when the last raw reading says the keyboard is back",
            0,
            hides(r),
        )
        assertTrue(
            "KeyboardHideHold: the tile must stay up when a take ends with the keyboard back inside the debounce window",
            r.tile.shown,
        )
    }
}
