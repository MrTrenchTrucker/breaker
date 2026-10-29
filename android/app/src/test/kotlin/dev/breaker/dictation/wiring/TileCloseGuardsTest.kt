package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user close cancels a take only in the states that call for it. Each test here sets up one state in
 * which the close must leave the take alone: the Home holds it, no take is in flight, a keyboard hide is held,
 * the text is on its way, the microphone is yielding, or a device failure runs. These tests pass on the base
 * as well, because the base never cancels; they pin what the close must not do.
 */
internal class TileCloseGuardsTest {

    /** A rig whose tile was brought up by a keyboard show, so the session has raised it. */
    private fun raisedByKeyboard(): TileRig {
        val rig = TileRig(arm = false)
        rig.tile.showResult = TileShow.NO_PERMISSION
        rig.coordinator.onArmedChanged(true)
        rig.tile.showResult = TileShow.SHOWN
        rig.coordinator.onKeyboardReading(true, true, 1000L)
        rig.settle()
        return rig
    }

    /** A coordinator over the rig's fakes whose take is the rig's take behind an owner, as the tile host builds it. */
    private fun ownedCoordinator(rig: TileRig, owner: TakeOwner): TileCoordinator =
        TileCoordinator(
            rig.tile, rig.main, rig.background, OwnedTakePort(rig.take, owner, TakeOwnerKind.TILE),
            rig.model, rig.notice, rig.opener, rig.clipboard, {}, rig.history,
        )

    @Test
    fun `a user close while the Home holds the take cancels nothing`() {
        val rig = TileRig(arm = false)
        val owner = TakeOwner()
        val coordinator = ownedCoordinator(rig, owner)
        coordinator.onArmedChanged(true)
        coordinator.onBegin()
        rig.settle()
        owner.begun(TakeOwnerKind.HOME)
        coordinator.onCloseRequested()
        rig.settle()
        assertEquals("app: a close while the Home holds the take must cancel nothing", 0, rig.take.cancelCalls)
        assertEquals(
            "app: a close while the Home holds the take must leave the take recording",
            DictationState.RECORDING,
            rig.take.sessionState,
        )
        assertFalse("app: a close while the Home holds the take must still hide the tile", rig.tile.shown)
    }

    @Test
    fun `a user close with no take in flight cancels nothing`() {
        val rig = TileRig()
        rig.coordinator.onCloseRequested()
        rig.settle()
        assertEquals("app: a close with no take in flight must cancel nothing", 0, rig.take.cancelCalls)
        assertFalse("app: a close with no take in flight must still hide the tile", rig.tile.shown)
    }

    @Test
    fun `a keyboard hide during a take is held and cancels nothing`() {
        val rig = raisedByKeyboard()
        rig.startRecording()
        rig.coordinator.onKeyboardReading(false, false, 2000L)
        rig.settle()
        assertEquals(
            "app: a keyboard hide during a take must make no hide call",
            0,
            rig.tile.calls.count { it == "hide" },
        )
        assertEquals("app: a keyboard hide during a take must cancel nothing", 0, rig.take.cancelCalls)
        assertEquals(
            "app: a keyboard hide during a take must leave the take recording",
            DictationState.RECORDING,
            rig.take.sessionState,
        )
    }

    @Test
    fun `a user close while the text is on its way cancels nothing`() {
        val rig = TileRig()
        rig.coordinator.onBegin()
        rig.coordinator.onSend()
        rig.coordinator.onCloseRequested()
        rig.settle()
        assertEquals("app: a close while the text is on its way must cancel nothing", 0, rig.take.cancelCalls)
        assertEquals(
            "app: a close while the text is on its way must let the send's finish run once",
            1,
            rig.take.finishCalls,
        )
    }

    @Test
    fun `a user close during a microphone yield keeps the answer of a begin that was pending`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Failed(FAILED_SENTENCE)
        rig.coordinator.onBegin()
        rig.coordinator.onTakeMicTaken()
        rig.coordinator.onCloseRequested()
        rig.settle()
        assertEquals("app: a close during the microphone yield must cancel nothing", 0, rig.take.cancelCalls)
        assertTrue(
            "app: the answer of a begin pending during the microphone yield must still reach the tile",
            rig.tile.notices.contains(FAILED_SENTENCE),
        )
    }

    @Test
    fun `a user close during a device failure keeps the answer of a begin that was pending`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Failed(FAILED_SENTENCE)
        rig.take.sessionState = DictationState.RECORDING
        rig.coordinator.onBegin()
        assertTrue("app: the microphone failure must be taken by the tile", rig.coordinator.onTakeFailed())
        rig.coordinator.onCloseRequested()
        rig.settle()
        assertEquals("app: a close during a device failure must cancel nothing", 0, rig.take.cancelCalls)
        assertTrue(
            "app: the answer of a begin pending during a device failure must still reach the tile",
            rig.tile.notices.contains(FAILED_SENTENCE),
        )
    }
}
