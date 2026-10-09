package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCoordinatorOutcomeTest {
    /** A sentence that must never reach the tile, a notice or the launcher. */
    private val privateText: String = "a private sentence for the leak check"

    @Test
    fun `a committed send pushes sending, then sent, then clears the notice`() {
        val rig = TileRig()
        rig.startRecording()
        rig.tile.calls.clear()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals(
            "app: a committed send should push SENDING, then SENT, then clear the notice",
            listOf("state:SENDING", "state:SENT", "clearNotice"),
            rig.tile.calls,
        )
    }

    @Test
    fun `a copied send ends on sent and shows no notice of its own`() {
        val rig = TileRig()
        rig.take.sendResult = sentResult(CommitOutcome.COPIED, null)
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a copied send should end on SENT", TileState.SENT, rig.tile.states.last())
        assertTrue("app: a copied send should show no notice of its own", rig.tile.notices.isEmpty())
    }

    @Test
    fun `a failed send ends on failed and shows its notice`() {
        val rig = TileRig()
        rig.take.sendResult = sentResult(CommitOutcome.FAILED, "The field refused the text.")
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: a failed send should end on FAILED", TileState.FAILED, rig.tile.states.last())
        assertEquals("app: a failed send should show its notice", listOf("The field refused the text."), rig.tile.notices)
    }

    @Test
    fun `a begin after a sent text pushes recording and sent is gone`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: the send should leave the tile on SENT", TileState.SENT, rig.tile.states.last())
        rig.tile.calls.clear()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a begin after a sent text should push RECORDING and not SENT", listOf("state:RECORDING"), rig.tile.calls)
    }

    @Test
    fun `a gesture begin while the tile shows sent starts a take`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a gesture begin while SENT should reach the take a second time", 2, rig.take.beginCalls)
    }

    @Test
    fun `a tap on the sent tile starts a take and never opens the launcher`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.coordinator.onTap()
        rig.settle()
        assertEquals("app: a tap while SENT should start a take", 2, rig.take.beginCalls)
        assertEquals("app: a tap while SENT must not open the launcher", emptyList<String?>(), rig.opener.routes)
    }

    @Test
    fun `cancel after a sent text pushes armed at once`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.tile.calls.clear()
        rig.coordinator.onCancel()
        assertEquals("app: cancel after SENT should clear the notice and push ARMED", listOf("clearNotice", "state:ARMED"), rig.tile.calls)
    }

    @Test
    fun `switching off and on again clears sent`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.tile.calls.clear()
        rig.coordinator.onArmedChanged(false)
        rig.coordinator.onArmedChanged(true)
        assertEquals(
            "app: switching off then on after SENT should hide, show and push ARMED, never SENT",
            listOf("state:IDLE", "hide", "show", "state:ARMED"),
            rig.tile.calls,
        )
        assertFalse("app: switching on must not push SENT again", rig.tile.calls.contains("state:SENT"))
    }

    @Test
    fun `switching on again while armed clears sent`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: the send should leave the tile on SENT before the switch", TileState.SENT, rig.tile.states.last())
        rig.tile.calls.clear()
        rig.coordinator.onArmedChanged(true)
        assertEquals("app: switching on while armed should show the tile and push ARMED, not SENT", listOf("show", "state:ARMED"), rig.tile.calls)
    }

    @Test
    fun `a missing model on a begin from sent shows armed and the notice and starts nothing`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.model.ready = false
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: a missing model on a begin from SENT should show ARMED", TileState.ARMED, rig.tile.states.last())
        assertEquals("app: a missing model on a begin from SENT should raise the missing notice", listOf("clear", "missing"), rig.notice.calls)
        assertEquals("app: a missing model on a begin from SENT should show the fixed sentence", listOf(ModelSentences.NO_MODEL), rig.tile.notices)
        assertEquals("app: a missing model must not start a second take", 1, rig.take.beginCalls)
    }

    @Test
    fun `the dictated text never reaches the tile, a notice or the launcher`() {
        val rig = TileRig()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription(privateText))
        rig.startRecording()
        rig.coordinator.onSend()
        rig.settle()
        rig.coordinator.onTap()
        rig.coordinator.onBegin()
        rig.settle()
        val recorded: List<String> = rig.tile.calls + rig.tile.notices + rig.notice.calls + rig.opener.routes.map { it.orEmpty() }
        for (entry in recorded) {
            assertFalse("app: the dictated text must never reach the tile, a notice or the launcher", entry.contains(privateText))
        }
    }
}
