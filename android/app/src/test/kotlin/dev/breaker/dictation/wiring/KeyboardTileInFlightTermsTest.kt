package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each part of the in-flight check on its own, as the keyboard hold and the keyboard show read it.
 * The check is TileCoordinator.inFlight: a begin pending, a send, a microphone yield, a device failure,
 * or the recording face. The device failure and the yield both set the send flag when they start, so
 * these tests start them while a begin is still pending and that begin is answered refused. The answer
 * clears the send flag, and the cut keeps its own part of the check.
 */
class KeyboardTileInFlightTermsTest {

    private fun hides(r: TileRig): Int = r.tile.calls.count { it == "hide" }

    private fun shows(r: TileRig): Int = r.tile.calls.count { it == "show" }

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

    /**
     * The raised tile with a begin pending: the runner already records, so the begin answers refused.
     * The begin's answer waits on the main queue, so a cut that arrives first runs before it.
     */
    private fun pendingRefusedBegin(): TileRig {
        val r = raisedByKeyboard()
        r.take.beginResult = BeginResult.Refused(REFUSED_SENTENCE)
        r.take.sessionState = DictationState.RECORDING
        r.coordinator.onBegin()
        r.background.runAll()
        return r
    }

    @Test
    fun `a switch off and on again drops a hide held during a microphone yield`() {
        val r = raisedByKeyboard()
        r.coordinator.onTakeMicTaken()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        assertTrue(
            "TileCoordinator.onKeyboardReading: a keyboard-gone reading during a microphone yield must hold the hide",
            r.tile.shown,
        )
        r.coordinator.onArmedChanged(false)
        assertEquals("TileCoordinator.disarm: the switch-off hides the tile once", 1, hides(r))
        r.coordinator.onArmedChanged(true)
        assertTrue(
            "TileCoordinator.disarm: a hide held before the switch went off must not hide the tile shown after it came back on",
            r.tile.shown,
        )
        r.settle()
        assertEquals("TileCoordinator.arm: no hide when the old yield settles after the switch came back on", 1, hides(r))
        assertTrue("TileCoordinator.arm: the tile stays up when the old yield settles", r.tile.shown)
    }

    @Test
    fun `a keyboard-gone reading during a device failure alone holds the hide until the failure settles`() {
        val r = pendingRefusedBegin()
        assertTrue("TileCoordinator.onTakeFailed: the cut must start", r.coordinator.onTakeFailed())
        r.take.sessionState = DictationState.SENDING
        r.main.drain()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        assertTrue(
            "TileCoordinator.inFlight: a keyboard-gone reading while only the device failure runs must hold the hide",
            r.tile.shown,
        )
        assertEquals("TileCoordinator.onKeyboardReading: no hide call while the failure is transcribed", 0, hides(r))
        r.background.runAll()
        r.main.drain()
        assertEquals(
            "TileCoordinator.push: exactly one hide when the device failure settles with the keyboard gone",
            1,
            hides(r),
        )
        assertFalse("TileCoordinator.push: the tile must be down after the device failure settles", r.tile.shown)
    }

    @Test
    fun `a keyboard-gone reading during a microphone yield alone holds the hide until the yield settles`() {
        val r = pendingRefusedBegin()
        r.coordinator.onTakeMicTaken()
        r.main.drain()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        assertTrue(
            "TileCoordinator.inFlight: a keyboard-gone reading while only the microphone yield runs must hold the hide",
            r.tile.shown,
        )
        assertEquals("TileCoordinator.onKeyboardReading: no hide call while the yield runs", 0, hides(r))
        r.settle()
        assertEquals("TileCoordinator.push: exactly one hide when the yield settles with the keyboard gone", 1, hides(r))
        assertFalse("TileCoordinator.push: the tile must be down after the yield settles", r.tile.shown)
    }

    @Test
    fun `a keyboard show during a device failure alone does not bring the closed tile back`() {
        val r = pendingRefusedBegin()
        assertTrue("TileCoordinator.onTakeFailed: the cut must start", r.coordinator.onTakeFailed())
        r.take.sessionState = DictationState.SENDING
        r.main.drain()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onKeyboardReading(true, true, 3000L)
        assertEquals("TileCoordinator.tileMayShow: no show call while only the device failure runs", 0, shows(r))
        assertFalse("TileCoordinator.tileMayShow: the closed tile stays down while the failure is transcribed", r.tile.shown)
    }

    @Test
    fun `a keyboard show during a microphone yield alone does not bring the closed tile back`() {
        val r = pendingRefusedBegin()
        r.coordinator.onTakeMicTaken()
        r.main.drain()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onKeyboardReading(true, true, 3000L)
        assertEquals("TileCoordinator.tileMayShow: no show call while only the microphone yield runs", 0, shows(r))
        assertFalse("TileCoordinator.tileMayShow: the closed tile stays down while the yield runs", r.tile.shown)
    }
}
