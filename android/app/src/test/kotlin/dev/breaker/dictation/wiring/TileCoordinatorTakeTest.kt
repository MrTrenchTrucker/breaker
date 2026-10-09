package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

class TileCoordinatorTakeTest {
    @Test
    fun `a begin with no speech model raises the missing notice and starts nothing`() {
        val rig = TileRig(ready = false)
        rig.tile.calls.clear()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: no model should raise the missing notice once", listOf("clear", "missing"), rig.notice.calls)
        assertEquals("app: no model must not start a take", 0, rig.take.beginCalls)
        assertEquals("app: no model must not queue anything", 0, rig.background.pending)
        assertEquals("app: no model leaves the tile armed, with no state push and one tile notice", listOf("notice"), rig.tile.calls)
        assertEquals("app: no model tells the tile the fixed sentence", listOf(ModelSentences.NO_MODEL), rig.tile.notices)
    }

    @Test
    fun `a begin runs on the background and the tile shows recording only after the answer is applied`() {
        val rig = TileRig()
        rig.coordinator.onBegin()
        assertEquals("app: begin must not run on the calling thread", 0, rig.take.beginCalls)
        rig.background.runAll()
        assertEquals("app: begin should run once on the background", 1, rig.take.beginCalls)
        assertEquals("app: the answer must wait for the main post", listOf(TileState.ARMED), rig.tile.states)
        rig.main.drain()
        assertEquals("app: a recording begin should show RECORDING", listOf(TileState.ARMED, TileState.RECORDING), rig.tile.states)
    }

    @Test
    fun `a second begin before the first answer is ignored`() {
        val rig = TileRig()
        rig.coordinator.onBegin()
        rig.coordinator.onBegin()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: three quick begins should reach the take once", 1, rig.take.beginCalls)
    }

    @Test
    fun `a begin is ignored unless the tile shows armed`() {
        val rig = TileRig()
        rig.take.beginResult = BeginResult.Failed(FAILED_SENTENCE)
        rig.coordinator.onBegin()
        rig.settle()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin on a FAILED tile must not reach the take", 1, rig.take.beginCalls)
    }

    @Test
    fun `a refused or failed begin shows failed with the sentence`() {
        val answers = listOf(
            BeginResult.Refused(REFUSED_SENTENCE),
            BeginResult.Failed(FAILED_SENTENCE),
        )
        for (answer in answers) {
            val rig = TileRig()
            rig.take.beginResult = answer
            rig.coordinator.onBegin()
            rig.settle()
            val sentence: String = if (answer is BeginResult.Refused) answer.sentence else (answer as BeginResult.Failed).sentence
            assertEquals("app: $answer should show FAILED", listOf(TileState.ARMED, TileState.FAILED), rig.tile.states)
            assertEquals("app: $answer should show its sentence", listOf(sentence), rig.tile.notices)
            assertEquals("app: the notice comes after the state, which would clear it", "state:FAILED", rig.tile.calls[rig.tile.calls.size - 2])
        }
    }

    @Test
    fun `a take that throws on begin shows a fixed sentence and can be tried again`() {
        val rig = TileRig()
        rig.take.beginError = IllegalStateException("secret detail")
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a throwing begin should show FAILED", TileState.FAILED, rig.tile.states.last())
        assertEquals("app: a throwing begin should show the fixed sentence", listOf("Breaker could not start recording."), rig.tile.notices)
        rig.coordinator.onTap()
        rig.take.beginError = null
        rig.startRecording()
        assertEquals("app: a begin after a throwing begin should be accepted", TileState.RECORDING, rig.tile.states.last())
    }

    @Test
    fun `a background that refuses the begin shows failed and can be tried again`() {
        val rig = TileRig()
        rig.background.submitError = IllegalStateException("closed")
        rig.coordinator.onBegin()
        assertEquals("app: a refused begin should show FAILED", TileState.FAILED, rig.tile.states.last())
        assertEquals("app: a refused begin should show the fixed sentence", listOf("Breaker could not start recording."), rig.tile.notices)
        rig.background.submitError = null
        rig.coordinator.onTap()
        rig.startRecording()
        assertEquals("app: a begin after a refusing background should be accepted", TileState.RECORDING, rig.tile.states.last())
    }

    @Test
    fun `the tile state comes from the map, not from the begin answer`() {
        val rig = TileRig()
        rig.take.mirrorRunner = false
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a Recording answer over an idle session should map to ARMED", TileState.ARMED, rig.tile.states.last())
        rig.take.sessionState = DictationState.TRANSCRIBING
        rig.coordinator.onTakeEnded()
        assertEquals("app: a transcribing session should map to RECORDING", TileState.RECORDING, rig.tile.states.last())
    }

    @Test
    fun `three sends before the tile shows sending finish once and send once`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.coordinator.onSend()
        rig.coordinator.onSend()
        assertEquals("app: the tile should show SENDING before the background runs", TileState.SENDING, rig.tile.states.last())
        rig.settle()
        assertEquals("app: repeated sends should finish exactly once", 1, rig.take.finishCalls)
        assertEquals("app: repeated sends should send exactly once", 1, rig.take.sendCalls)
        assertEquals(
            "app: the states should be armed, recording, sending, armed",
            listOf(TileState.ARMED, TileState.RECORDING, TileState.SENDING, TileState.ARMED),
            rig.tile.states,
        )
    }

    @Test
    fun `a send that commits clears the notice and shows armed`() {
        val rig = TileRig()
        rig.startRecording()
        rig.tile.calls.clear()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a committed send should push ARMED and then clear the notice", listOf("state:SENDING", "state:ARMED", "clearNotice"), rig.tile.calls)
        assertEquals("app: a committed send shows no notice", emptyList<String>(), rig.tile.notices)
    }

    @Test
    fun `a copied text counts as sent and a failed commit shows its detail or a fixed sentence`() {
        val copied = TileRig()
        copied.take.sendResult = sentResult(CommitOutcome.COPIED, null)
        copied.startRecording()
        copied.coordinator.onSend()
        copied.settle()
        assertEquals("app: a copied text should end on ARMED", TileState.ARMED, copied.tile.states.last())
        val detailed = TileRig()
        detailed.take.sendResult = sentResult(CommitOutcome.FAILED, "Putting text into a field is not available yet.")
        detailed.startRecording()
        detailed.coordinator.onSend()
        detailed.settle()
        assertEquals("app: a failed commit should end on FAILED", TileState.FAILED, detailed.tile.states.last())
        assertEquals("app: a failed commit should show its detail", listOf("Putting text into a field is not available yet."), detailed.tile.notices)
        val bare = TileRig()
        bare.take.sendResult = sentResult(CommitOutcome.FAILED, "  ")
        bare.startRecording()
        bare.coordinator.onSend()
        bare.settle()
        assertEquals("app: a failed commit with a blank detail should show the fixed sentence", listOf("Breaker could not finish listening."), bare.tile.notices)
    }

    @Test
    fun `a send that answers nothing shows failed`() {
        val rig = TileRig()
        rig.take.sendResult = null
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a null send should show FAILED", TileState.FAILED, rig.tile.states.last())
        assertEquals("app: a null send should show the fixed sentence", listOf("Breaker could not finish listening."), rig.tile.notices)
    }

    @Test
    fun `an empty take is not a failure and sends nothing`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.Failed("No audio was captured")
        rig.startRecording()
        rig.tile.calls.clear()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: an empty take should go SENDING then ARMED", listOf("state:SENDING", "state:ARMED"), rig.tile.calls)
        assertEquals("app: an empty take must not send", 0, rig.take.sendCalls)
        assertEquals("app: an empty take must finish once", 1, rig.take.finishCalls)
    }

    @Test
    fun `any other failed finish shows failed with its sentence and frees the next send`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.Failed("Nothing was heard.")
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a failed finish should show FAILED", TileState.FAILED, rig.tile.states.last())
        assertEquals("app: a failed finish should show its sentence", listOf("Nothing was heard."), rig.tile.notices)
        assertEquals("app: a failed finish must not send", 0, rig.take.sendCalls)
        rig.coordinator.onTap()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a send after a failed finish should finish again", 2, rig.take.finishCalls)
    }

    @Test
    fun `a transcription with only blanks is dropped and not sent`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("  \n "))
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a blank text must not be sent", 0, rig.take.sendCalls)
        assertEquals("app: a blank text should drop the take once", 1, rig.take.cancelCalls)
        assertEquals("app: a blank text should end on ARMED", TileState.ARMED, rig.tile.states.last())
        assertEquals("app: a blank text shows no notice", emptyList<String>(), rig.tile.notices)
    }

    @Test
    fun `a take that throws on finish or send is dropped and shows a fixed sentence`() {
        val onFinish = TileRig()
        onFinish.take.finishError = IllegalStateException("secret detail")
        onFinish.startRecording()
        onFinish.coordinator.onSend()
        onFinish.settle()
        assertEquals("app: a throwing finish should show FAILED", TileState.FAILED, onFinish.tile.states.last())
        assertEquals("app: a throwing finish should show the fixed sentence", listOf("Breaker could not finish listening."), onFinish.tile.notices)
        assertEquals("app: a throwing finish should drop the take once", 1, onFinish.take.cancelCalls)
        val onSend = TileRig()
        onSend.take.sendError = IllegalStateException("secret detail")
        onSend.startRecording()
        onSend.coordinator.onSend()
        onSend.settle()
        assertEquals("app: a throwing send should show FAILED", TileState.FAILED, onSend.tile.states.last())
        assertEquals("app: a throwing send should show the fixed sentence", listOf("Breaker could not finish listening."), onSend.tile.notices)
        assertEquals("app: a throwing send should drop the take once", 1, onSend.take.cancelCalls)
        assertEquals("app: a throwing send was tried once", 1, onSend.take.sendCalls)
    }

    @Test
    fun `a throwing drop after a failed send still shows the failure`() {
        val rig = TileRig()
        rig.take.sendError = IllegalStateException("secret detail")
        rig.take.cancelError = IllegalStateException("cancel failed")
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a drop that throws should not hide the failure", TileState.FAILED, rig.tile.states.last())
    }

    @Test
    fun `a background that refuses the send shows failed`() {
        val rig = TileRig()
        rig.startRecording()
        rig.background.submitError = IllegalStateException("closed")
        rig.coordinator.onSend()
        assertEquals("app: a refused send should show FAILED", TileState.FAILED, rig.tile.states.last())
        assertEquals("app: a refused send should show the fixed sentence", listOf("Breaker could not finish listening."), rig.tile.notices)
        assertEquals("app: a refused send must not finish", 0, rig.take.finishCalls)
    }

    @Test
    fun `the answer of a take is applied only on the main post`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        val before = rig.tile.calls.size
        rig.background.runAll()
        assertEquals("app: the background must not touch the tile", before, rig.tile.calls.size)
        assertEquals("app: the answer should be waiting for the main post", 1, rig.main.pending)
        rig.main.drain()
        assertEquals("app: the main post should show ARMED", TileState.ARMED, rig.tile.states.last())
    }

    @Test
    fun `a switch-off while the text is on its way frees the next send`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.coordinator.onArmedChanged(false)
        rig.settle()
        rig.coordinator.onArmedChanged(true)
        rig.take.sessionState = DictationState.RECORDING
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a send after a switch-off and on should finish again", 2, rig.take.finishCalls)
    }
}
