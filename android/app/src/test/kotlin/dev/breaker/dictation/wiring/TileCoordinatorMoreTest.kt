package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

class TileCoordinatorMoreTest {
    @Test
    fun `a tile that failed comes back armed with no stale notice after switching off and on`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Failed(FAILED_SENTENCE)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: the failed begin should leave the tile on FAILED", TileState.FAILED, rig.tile.states.last())
        rig.coordinator.onArmedChanged(false)
        rig.background.runAll()
        rig.tile.calls.clear()
        rig.coordinator.onArmedChanged(true)
        assertEquals("app: switching on should show the tile and push ARMED, not FAILED", listOf("show", "state:ARMED"), rig.tile.calls)
        assertEquals("app: the tile should end on ARMED after off and on", TileState.ARMED, rig.tile.states.last())
        assertEquals("app: no new notice appears after off and on", listOf(FAILED_SENTENCE), rig.tile.notices)
    }

    @Test
    fun `a transcription with only blanks drops the take and leaves the tile armed with no extra calls`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("  \n "))
        rig.startRecording()
        rig.tile.calls.clear()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a blank text must not be sent", 0, rig.take.sendCalls)
        assertEquals("app: a blank text should go SENDING then ARMED and clear nothing", listOf("state:SENDING", "state:ARMED"), rig.tile.calls)
        assertEquals("app: a blank text should end on ARMED", TileState.ARMED, rig.tile.states.last())
        assertEquals("app: a blank text shows no notice", emptyList<String>(), rig.tile.notices)
    }
}
