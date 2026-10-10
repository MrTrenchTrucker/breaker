package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guards of the taken branch, driven at the coordinator level over a fresh TileRig each time.
 * A cancel is refused while a taken transcription runs, and a switch off clears the taken hold.
 */
class TakeYieldGuardTest {

    @Test
    fun `after a switch off during a taken transcription a begin and a cancel after a send are accepted`() {
        val rig = TileRig()
        rig.coordinator.onTakeMicTaken()
        rig.coordinator.onArmedChanged(false)
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        // The begin reaches the take and is accepted.
        assertEquals(1, rig.take.beginCalls)
        rig.coordinator.onSend()
        val queued: Int = rig.background.pending
        rig.coordinator.onCancel()
        // The cancel is accepted: the tile shows ARMED at once and one more block is queued on the background.
        assertEquals(TileState.ARMED, rig.tile.states.last())
        assertEquals(queued + 1, rig.background.pending)
    }

    @Test
    fun `after a taken transcription is cut a cancel is refused and the take is never cancelled`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        rig.coordinator.onCancel()
        rig.settle()
        // The cancel is refused while the taken branch transcribes, so the take is never cancelled.
        assertEquals(0, rig.take.cancelCalls)
    }
}
