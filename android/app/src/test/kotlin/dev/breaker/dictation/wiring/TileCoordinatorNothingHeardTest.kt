package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Upper bound on settle passes, so a block that keeps posting itself fails by name instead of hanging. */
private const val MAX_PASSES: Int = 1_000

/**
 * A take whose text is blank shows the nothing heard notice after the armed state and sends nothing.
 * A take with no audio stays silent, and a text with words is still sent.
 */
class TileCoordinatorNothingHeardTest {

    @Test
    fun `an empty text is dropped with the nothing heard notice shown once after the armed state`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription(""))
        rig.startRecording()
        rig.tile.calls.clear()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: an empty text must not be sent", 0, rig.take.sendCalls)
        assertEquals("app: an empty text drops the take once", 1, rig.take.cancelCalls)
        assertEquals("app: an empty text should end on ARMED", TileState.ARMED, rig.tile.states.last())
        assertEquals("app: an empty text should show the notice once", listOf(RunnerSentences.NOTHING_HEARD), rig.tile.notices)
        assertEquals("app: the notice comes after the armed state", listOf("state:SENDING", "state:ARMED", "notice"), rig.tile.calls)
        assertEquals("app: an empty text must not reach the history", 0, rig.history.saved.size)
    }

    @Test
    fun `a whitespace-only text is dropped with the nothing heard notice and never sent`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription(" \t\n  "))
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a whitespace-only text must not be sent", 0, rig.take.sendCalls)
        assertEquals("app: a whitespace-only text drops the take once", 1, rig.take.cancelCalls)
        assertEquals("app: a whitespace-only text shows the notice once", listOf(RunnerSentences.NOTHING_HEARD), rig.tile.notices)
        assertEquals("app: a whitespace-only text should end on ARMED", TileState.ARMED, rig.tile.states.last())
    }

    @Test
    fun `a text with words is still sent and shows no notice`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a text with words must be sent once", 1, rig.take.sendCalls)
        assertEquals("app: a text with words shows no notice", emptyList<String>(), rig.tile.notices)
        assertEquals("app: a text with words ends on SENT", TileState.SENT, rig.tile.states.last())
    }

    @Test
    fun `a take with no audio is silent and never sent`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.Failed(EMPTY_TAKE_SENTENCE)
        rig.startRecording()
        rig.tile.calls.clear()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a take with no audio must not be sent", 0, rig.take.sendCalls)
        assertEquals("app: a take with no audio goes SENDING then ARMED and shows nothing", listOf("state:SENDING", "state:ARMED"), rig.tile.calls)
        assertEquals("app: a take with no audio shows no notice", emptyList<String>(), rig.tile.notices)
    }

    @Test
    fun `a switch-off during the finish of a blank text shows no notice`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("  "))
        rig.startRecording()
        rig.coordinator.onSend()
        rig.coordinator.onArmedChanged(false)
        rig.settle()
        assertEquals("app: the finish ran once", 1, rig.take.finishCalls)
        assertEquals("app: a late blank answer after the switch-off shows no notice", emptyList<String>(), rig.tile.notices)
        assertEquals("app: a late blank answer after the switch-off must not send", 0, rig.take.sendCalls)
    }

    @Test
    fun `a quiet take over the real runner shows the notice once and keeps no History row`() {
        val committer = FakeCommitter()
        val rig = Rig(localEngine = FakeSttEngine(SttResult.Success("")), committer = committer)
        val tile = RecordingTile()
        val main = ManualMain()
        val background = ManualBackground()
        val coordinator = TileCoordinator(
            tile = tile,
            main = main,
            background = background,
            take = TakePortAdapter(rig.runner),
            modelReady = FakeModelReady(true),
            modelNotice = RecordingModelNotice(),
            opener = RecordingOpener(),
            history = rig.history,
        )
        coordinator.onArmedChanged(true)
        coordinator.onBegin()
        settleAll(background, main)
        assertEquals("app: the real runner should be listening", DictationState.RECORDING, rig.runner.sessionState)
        rig.speak()
        tile.calls.clear()
        coordinator.onSend()
        settleAll(background, main)
        assertEquals("app: a quiet take must leave the real session idle", DictationState.IDLE, rig.runner.sessionState)
        assertEquals("app: a quiet take must not commit", 0, committer.commits)
        assertEquals("app: a quiet take must keep no History row", 0, rig.history.saved.size)
        assertEquals("app: a quiet take shows the notice once", listOf(RunnerSentences.NOTHING_HEARD), tile.notices)
        assertEquals("app: a quiet take goes SENDING, then ARMED, then the notice", listOf("state:SENDING", "state:ARMED", "notice"), tile.calls)
    }

    /** Runs the background and then the main queue until both are empty. */
    private fun settleAll(background: ManualBackground, main: ManualMain) {
        var passes = 0
        while (background.pending > 0 || main.pending > 0) {
            passes += 1
            check(passes <= MAX_PASSES) { "app: the nothing heard rig did not settle after $MAX_PASSES passes" }
            background.runAll()
            main.drain()
        }
    }
}
