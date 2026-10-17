package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The tile over the real runner and a microphone that delivers no audio: an empty take is not a failure. */
class TileEmptyTakeRealRunnerTest {
    @Test
    fun `a take with no audio sends nothing and the tile goes back to armed without a notice`() {
        val committer = FakeCommitter()
        val rig = Rig(committer = committer)
        val tile = RecordingTile()
        val main = ManualMain()
        val background = ManualBackground()
        val coordinator = TileCoordinator(
            tile,
            main,
            background,
            TakePortAdapter(rig.runner),
            FakeModelReady(true),
            RecordingModelNotice(),
            RecordingOpener(),
        )
        coordinator.onArmedChanged(true)
        coordinator.onBegin()
        background.runAll()
        main.drain()
        assertEquals("app: the real runner should be listening", DictationState.RECORDING, rig.runner.sessionState)
        assertEquals("app: the tile should show RECORDING", TileState.RECORDING, tile.states.last())
        coordinator.onSend()
        background.runAll()
        main.drain()
        assertEquals("app: the real runner should have stopped the audio once", 1, rig.audio.stops)
        assertEquals("app: an empty take should leave the real session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: an empty take should end on ARMED", TileState.ARMED, tile.states.last())
        assertEquals("app: an empty take is not a failure and shows no notice", emptyList<String>(), tile.notices)
        assertEquals("app: an empty take must not commit", 0, committer.commits)
        assertEquals("app: an empty take must not reach the history", 0, rig.history.saved.size)
    }
}
