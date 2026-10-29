package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A user close that lands on a recording take, or on a begin still pending, ends that take the way Cancel
 * ends it, and then hides the tile. The coordinator, the take and the tile are the real classes over the fakes.
 */
internal class TileCloseCancelsTakeTest {

    /** Holds each submitted block until a test runs the next one, so an answer can land between two steps. */
    private class StepBackground : Background {
        private val queue: ArrayDeque<() -> Unit> = ArrayDeque()

        override fun submit(block: () -> Unit) {
            queue.addLast(block)
        }

        fun runNext() {
            check(queue.isNotEmpty()) { "app: no queued block to run" }
            val next: () -> Unit = queue.removeFirst()
            next()
        }
    }

    /** A coordinator over the rig's fakes whose take is the rig's take behind an owner, as the tile host builds it. */
    private fun ownedCoordinator(rig: TileRig, owner: TakeOwner): TileCoordinator =
        TileCoordinator(
            rig.tile, rig.main, rig.background, OwnedTakePort(rig.take, owner, TakeOwnerKind.TILE),
            rig.model, rig.notice, rig.opener, rig.clipboard, {}, rig.history,
        )

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

    @Test
    fun `a user close during a take ends it like Cancel and sends nothing`() {
        val rig = TileRig()
        rig.startRecording()
        rig.tile.calls.clear()
        rig.coordinator.onCloseRequested()
        rig.settle()
        assertEquals("app: a close during a take must cancel the take once", 1, rig.take.cancelCalls)
        assertEquals("app: a close during a take must finish nothing", 0, rig.take.finishCalls)
        assertEquals("app: a close during a take must send nothing", 0, rig.take.sendCalls)
        assertEquals(
            "app: a close during a take must leave the session IDLE",
            DictationState.IDLE,
            rig.take.sessionState,
        )
        assertEquals("app: a close during a take must leave the tile ARMED", TileState.ARMED, rig.tile.states.last())
        assertEquals("app: a close during a take must end with the hide", "hide", rig.tile.calls.last())

        val cancelled = TileRig()
        cancelled.startRecording()
        cancelled.tile.calls.clear()
        cancelled.coordinator.onCancel()
        cancelled.settle()
        assertEquals("app: Cancel must cancel the take once too", 1, cancelled.take.cancelCalls)
        assertEquals(
            "app: the close must add only the hide to what Cancel does on the tile",
            cancelled.tile.calls,
            rig.tile.calls.dropLast(1),
        )
        assertEquals(
            "app: the close must leave the same session as Cancel",
            cancelled.take.sessionState,
            rig.take.sessionState,
        )
    }

    @Test
    fun `a user close during a pending begin cancels it and no recording face follows`() {
        val background = StepBackground()
        val main = ManualMain()
        val tile = RecordingTile()
        val take = FakeTake()
        val coordinator = TileCoordinator(
            tile, main, background, take, FakeModelReady(true), RecordingModelNotice(), RecordingOpener(),
            RecordingClipboard(), {}, RecordingHistory(),
        )
        coordinator.onArmedChanged(true)
        coordinator.onBegin()
        coordinator.onCloseRequested()
        background.runNext()
        main.drain()
        background.runNext()
        main.drain()
        assertEquals("app: the queued begin must run once before its cancel", 1, take.beginCalls)
        assertEquals("app: a close during a pending begin must cancel the take once", 1, take.cancelCalls)
        assertFalse(
            "app: no RECORDING face may follow a close during a pending begin",
            tile.states.contains(TileState.RECORDING),
        )
        tile.calls.clear()
        coordinator.onShake()
        assertEquals(
            "app: after the close a shake must show the tile once, so no begin is still pending",
            1,
            tile.calls.count { it == "show" },
        )
    }

    @Test
    fun `a user close during a begin started from the busy face clears the busy face`() {
        val rig = TileRig()
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        assertEquals("app: the cut must leave the busy face", TileState.MIC_BUSY, rig.tile.states.last())
        rig.coordinator.onBegin()
        rig.coordinator.onCloseRequested()
        rig.settle()
        assertEquals(
            "app: a close during a begin from the busy face must cancel the take once",
            1,
            rig.take.cancelCalls,
        )
        assertEquals(
            "app: a close during a begin from the busy face must clear the busy face",
            TileState.ARMED,
            rig.tile.states.last(),
        )
    }

    @Test
    fun `a user close of a take the tile holds cancels it`() {
        val rig = TileRig(arm = false)
        val coordinator = ownedCoordinator(rig, TakeOwner())
        coordinator.onArmedChanged(true)
        coordinator.onBegin()
        rig.settle()
        coordinator.onCloseRequested()
        rig.settle()
        assertEquals("app: a close of a take the tile holds must cancel it once", 1, rig.take.cancelCalls)
        assertEquals(
            "app: a close of a take the tile holds must leave the session IDLE",
            DictationState.IDLE,
            rig.take.sessionState,
        )
    }

    @Test
    fun `a user close drops a held keyboard hide before the cancel can apply it`() {
        val rig = raisedByKeyboard()
        rig.startRecording()
        rig.coordinator.onKeyboardReading(false, false, 2000L)
        rig.settle()
        assertEquals("app: the keyboard hide must be held during the take", 0, rig.tile.calls.count { it == "hide" })
        rig.coordinator.onCloseRequested()
        rig.settle()
        assertEquals(
            "app: the close must hide the tile once and the held keyboard hide must add no hide",
            1,
            rig.tile.calls.count { it == "hide" },
        )
        assertEquals("app: the close must cancel the take once", 1, rig.take.cancelCalls)
    }
}
