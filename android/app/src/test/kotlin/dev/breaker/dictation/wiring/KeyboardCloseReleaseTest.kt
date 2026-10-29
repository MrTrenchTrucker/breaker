package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A user close takes the tile down for good: a keyboard-gone hide that was held for a take is dropped,
 * and the raised mark is released, so nothing automatic hides the tile after the close. A shake that
 * shows the tile again starts from a clean mark. Each test counts the hide calls from the close on: the
 * close's own hide is counted before the test continues, and the later readings must add no hide.
 * The rig and the fakes are the ones in TileFakes.kt.
 */
class KeyboardCloseReleaseTest {

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
    fun `a close during a take drops the held hide, so the take end applies no hide`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("the gone hide is held while the take records", 0, hides(r))
        r.coordinator.onCloseRequested()
        r.settle()
        // The close's own hide is the one hide counted here; the hide count from the close on is compared below.
        val afterClose: Int = hides(r)
        assertEquals("the close hides the tile once", 1, afterClose)
        assertFalse("the close takes the tile down", r.tile.shown)
        r.take.sessionState = DictationState.IDLE
        r.coordinator.onTakeEnded()
        r.settle()
        assertEquals("the take end applies no hide after the close", afterClose, hides(r))
        // Probe: the raised mark must be released. A keyboard that leaves focus, then goes, is a change
        // inside the session; a keyboard-gone reading with the mark still set would hide the tile again.
        r.coordinator.onKeyboardReading(true, false, 3000L)
        r.settle()
        r.coordinator.onKeyboardReading(false, false, 4000L)
        r.settle()
        assertEquals("the released raised mark makes a later gone reading a no-op", afterClose, hides(r))
    }

    @Test
    fun `a close with no held hide leaves a later keyboard gone reading with no hide`() {
        val r = raisedByKeyboard()
        r.coordinator.onCloseRequested()
        r.settle()
        // The close's own hide is excluded from the comparison: the count is taken right after it.
        val afterClose: Int = hides(r)
        assertEquals("the close hides the tile once", 1, afterClose)
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("the keyboard gone reading after the close makes no hide call", afterClose, hides(r))
    }

    @Test
    fun `a close during a take with no held hide leaves a keyboard gone reading in flight and at take end with no hide`() {
        val r = raisedByKeyboard()
        r.startRecording()
        r.coordinator.onCloseRequested()
        r.settle()
        // The close's own hide is excluded from the comparison: the count is taken right after it.
        val afterClose: Int = hides(r)
        assertEquals("the close hides the tile once", 1, afterClose)
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("the keyboard gone reading during the take makes no hide call", afterClose, hides(r))
        r.take.sessionState = DictationState.IDLE
        r.coordinator.onTakeEnded()
        r.settle()
        assertEquals("the take end applies no hide after the close", afterClose, hides(r))
    }

    @Test
    fun `a close, then a shake that shows the tile, then a keyboard gone reading leaves the tile up`() {
        val r = raisedByKeyboard()
        r.coordinator.onCloseRequested()
        r.settle()
        r.coordinator.onShake()
        r.settle()
        assertTrue("the shake shows the closed tile again", r.tile.shown)
        // The count is taken after the shake: the keyboard gone reading that follows must add no hide.
        val afterShake: Int = hides(r)
        r.coordinator.onKeyboardReading(false, false, 2000L)
        r.settle()
        assertEquals("the keyboard gone reading after the shake makes no hide call", afterShake, hides(r))
        assertTrue("the tile stays up after the keyboard goes", r.tile.shown)
    }
}
