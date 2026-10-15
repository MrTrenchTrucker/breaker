package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coordinator's device failure entry, over a fresh tile rig each time (the rig is armed). */
internal class TileCoordinatorDeviceFailureTest {

    @Test
    fun `a device failure shows sending while it transcribes then failed with the saved sentence and no busy face`() {
        val rig = TileRig()
        rig.startRecording()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        assertTrue("device: the cut is taken by the tile", rig.coordinator.onTakeFailed())
        assertEquals("device: the face is sending while the text is transcribed", TileState.SENDING, rig.tile.states.last())
        rig.settle()
        assertEquals("device: the face ends failed", TileState.FAILED, rig.tile.states.last())
        assertEquals("device: the sentence is the saved and copied one", listOf(DeviceFailureSentences.SAVED_AND_COPIED), rig.tile.notices)
        assertFalse("device: the busy face is never used", rig.tile.states.contains(TileState.MIC_BUSY))
        assertEquals("device: the text is stored once", 1, rig.history.saved.size)
        assertEquals("device: the text is copied once", listOf("captured text"), rig.clipboard.copies)
    }

    @Test
    fun `a device failure is not taken when the tile is not armed`() {
        val rig = TileRig(arm = false)
        rig.take.sessionState = DictationState.RECORDING
        assertFalse("device: an unarmed tile does not take the cut", rig.coordinator.onTakeFailed())
        rig.settle()
        assertEquals("device: an unarmed tile starts no finish", 0, rig.take.finishCalls)
    }

    @Test
    fun `a device failure is not taken when a send is already pushed`() {
        val rig = TileRig()
        rig.startRecording()
        rig.coordinator.onSend()
        assertFalse("device: a send on its way is not taken again", rig.coordinator.onTakeFailed())
    }

    @Test
    fun `a device failure is not taken when the session is not recording`() {
        val rig = TileRig()
        assertEquals("device: the session is idle before the cut", DictationState.IDLE, rig.take.sessionState)
        assertFalse("device: an idle session is not taken", rig.coordinator.onTakeFailed())
        rig.settle()
        assertEquals("device: an idle session starts no finish", 0, rig.take.finishCalls)
    }

    @Test
    fun `a device failure is not taken again while one is running`() {
        val rig = TileRig()
        rig.startRecording()
        assertTrue("device: the first cut is taken", rig.coordinator.onTakeFailed())
        assertFalse("device: a second cut while one runs is not taken", rig.coordinator.onTakeFailed())
        rig.settle()
        assertEquals("device: the take is finished once", 1, rig.take.finishCalls)
    }

    @Test
    fun `begin cancel and send are ignored while a device failure runs`() {
        val rig = TileRig()
        rig.startRecording()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        assertTrue("device: the cut is taken", rig.coordinator.onTakeFailed())
        rig.coordinator.onBegin()
        rig.coordinator.onCancel()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("device: no begin reaches the take while the cut runs", 1, rig.take.beginCalls)
        assertEquals("device: no cancel reaches the take while the cut runs", 0, rig.take.cancelCalls)
        assertEquals("device: no send reaches the take while the cut runs", 0, rig.take.sendCalls)
    }

    @Test
    fun `a switch off during a device failure drops the late answer`() {
        val rig = TileRig()
        rig.startRecording()
        assertTrue("device: the cut is taken", rig.coordinator.onTakeFailed())
        rig.coordinator.onArmedChanged(false)
        rig.settle()
        assertEquals("device: the late answer shows no sentence", emptyList<String>(), rig.tile.notices)
        assertEquals("device: the tile ends idle after the switch off", TileState.IDLE, rig.tile.states.last())
    }

    @Test
    fun `a refused background makes the cut not taken and leaves the session recording`() {
        val rig = TileRig()
        rig.startRecording()
        rig.background.submitError = RuntimeException("the background refused the work")
        assertFalse("device: a refused start is not taken", rig.coordinator.onTakeFailed())
        assertEquals("device: a refused start leaves the session recording", DictationState.RECORDING, rig.take.sessionState)
        assertEquals("device: a refused start shows the recording face again", TileState.RECORDING, rig.tile.states.last())
        assertEquals("device: a refused start posts no sentence", emptyList<String>(), rig.tile.notices)
    }

    @Test
    fun `a switch off clears a cut in flight so a second arm takes the next cut`() {
        val rig = TileRig()
        rig.startRecording()
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        assertTrue("device: the first cut is taken", rig.coordinator.onTakeFailed())
        rig.coordinator.onArmedChanged(false)
        rig.coordinator.onArmedChanged(true)
        assertTrue("device: after a switch off and on the next cut is taken, so the switch off cleared the running flag", rig.coordinator.onTakeFailed())
        rig.settle()
        assertEquals("device: only the second cut shows a sentence", listOf(DeviceFailureSentences.SAVED_AND_COPIED), rig.tile.notices)
    }
}
