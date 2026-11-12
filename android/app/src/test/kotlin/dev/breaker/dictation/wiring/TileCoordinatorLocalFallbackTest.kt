package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The push of SENT_LOCAL through the coordinator: the take's commit path knows
 * which path produced the text (the fallback flag on the finished
 * transcription), and the tile shows SENT_LOCAL for a committed or copied
 * fallback text. A server commit still shows SENT, and a begin clears the
 * flag, so a later server take shows SENT again.
 */
class TileCoordinatorLocalFallbackTest {

    private fun fallbackTranscription(text: String = "local words"): Transcription =
        Transcription(
            id = "t-1",
            text = text,
            source = TranscriptionSource.LOCAL,
            model = "small",
            durationMs = 100L,
            createdAt = 1_000L,
            serverPathFailed = true,
        )

    @Test
    fun `a committed fallback text ends on sent local`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(fallbackTranscription())
        rig.take.sendResult = sentResult(CommitOutcome.COMMITTED, null)
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals(
            "app: a committed fallback text should end on SENT_LOCAL",
            TileState.SENT_LOCAL,
            rig.tile.states.last(),
        )
    }

    @Test
    fun `a copied fallback text ends on sent local`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(fallbackTranscription())
        rig.take.sendResult = sentResult(CommitOutcome.COPIED, null)
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals(
            "app: a copied fallback text should end on SENT_LOCAL",
            TileState.SENT_LOCAL,
            rig.tile.states.last(),
        )
    }

    @Test
    fun `a committed server text ends on sent, not sent local`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("server words"))
        rig.take.sendResult = sentResult(CommitOutcome.COMMITTED, null)
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals(
            "app: a committed server text should end on SENT",
            TileState.SENT,
            rig.tile.states.last(),
        )
    }

    @Test
    fun `a begin after a fallback sent clears the flag so the next server send shows sent`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(fallbackTranscription())
        rig.take.sendResult = sentResult(CommitOutcome.COMMITTED, null)
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: the first send should end on SENT_LOCAL", TileState.SENT_LOCAL, rig.tile.states.last())

        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("server words"))
        rig.take.sendResult = sentResult(CommitOutcome.COMMITTED, null)
        rig.coordinator.onBegin()
        rig.settle()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals(
            "app: after a begin the flag must be clear, so the server send shows SENT",
            TileState.SENT,
            rig.tile.states.last(),
        )
    }

    @Test
    fun `a failed send after a fallback finish ends on failed, not sent local`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(fallbackTranscription())
        rig.take.sendResult = sentResult(CommitOutcome.FAILED, "The field refused the text.")
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals(
            "app: a failed commit must win over the fallback flag",
            TileState.FAILED,
            rig.tile.states.last(),
        )
    }

    @Test
    fun `a begin while sent local starts a take`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(fallbackTranscription())
        rig.take.sendResult = sentResult(CommitOutcome.COMMITTED, null)
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals(
            "app: a begin while SENT_LOCAL should start a take",
            2,
            rig.take.beginCalls,
        )
    }

    @Test
    fun `a missing model on a begin from sent local shows armed`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(fallbackTranscription())
        rig.take.sendResult = sentResult(CommitOutcome.COMMITTED, null)
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.model.ready = false
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals(
            "app: a missing model on a begin from SENT_LOCAL should show ARMED",
            TileState.ARMED,
            rig.tile.states.last(),
        )
        assertEquals(
            "app: a missing model on a begin from SENT_LOCAL should raise the missing notice",
            listOf("clear", "missing"),
            rig.notice.calls,
        )
        assertEquals(
            "app: a missing model on a begin from SENT_LOCAL must not start a second take",
            1,
            rig.take.beginCalls,
        )
    }

    @Test
    fun `a tap on sent local starts a take, never opens launcher`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(fallbackTranscription())
        rig.take.sendResult = sentResult(CommitOutcome.COMMITTED, null)
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.coordinator.onTap()
        rig.settle()
        assertEquals(
            "app: a tap while SENT_LOCAL should start a take",
            2,
            rig.take.beginCalls,
        )
        assertEquals(
            "app: a tap while SENT_LOCAL must not open the launcher",
            emptyList<String?>(),
            rig.opener.routes,
        )
    }
}
