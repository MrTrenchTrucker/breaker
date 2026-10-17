package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The taken-entry path when the background refuses the work. A refusing background throws from its
 * submit, so the block is never queued: the cut is abandoned at once, on the calling thread.
 * Each test builds a fresh rig whose background refuses the first submit.
 */
class TakeYieldRefusedQueueTest {

    /** A fresh rig whose taken-notice lambda collects every posted line, and whose background refuses. */
    private fun refusingRig(lines: ArrayList<String?>): TileRig {
        val rig = TileRig(takenNotice = { lines.add(it) }, clipboard = RecordingClipboard(), history = RecordingHistory())
        rig.background.submitError = RuntimeException("the background refused the work")
        return rig
    }

    @Test
    fun `a refused background posts the lead and then the not-converted line only`() {
        val lines = ArrayList<String?>()
        val rig = refusingRig(lines)
        rig.take.sessionState = DictationState.SENDING
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        assertEquals(
            "app: the refused cut posts the lead line and then the not-converted line",
            listOf(TakenSentences.LEAD, TakenSentences.NOT_CONVERTED),
            lines,
        )
        assertEquals("app: the refused cut queues no background block", 0, rig.background.pending)
        assertEquals("app: the refused cut posts nothing to the main thread", 0, rig.main.pending)
        assertFalse("app: the taken branch is no longer running after the refusal", rig.coordinator.yield.yieldRunning())
        assertEquals("app: the face stays the busy face after the refusal", TileState.MIC_BUSY, rig.tile.states.last())
        assertEquals("app: the refused cut resets the take to idle", DictationState.IDLE, rig.take.sessionState)
        assertEquals("app: the finish never runs on the refused path", 0, rig.take.finishCalls)
        assertEquals("app: nothing is saved on the refused path", 0, rig.history.saved.size)
        assertEquals("app: nothing is copied on the refused path", emptyList<String>(), rig.clipboard.copies)
        assertEquals("app: the tile shows no notice on the taken path", emptyList<String>(), rig.tile.notices)
    }

    @Test
    fun `a tap on the busy face after a refused cut starts a take`() {
        val lines = ArrayList<String?>()
        val rig = refusingRig(lines)
        rig.coordinator.onTakeMicTaken()
        rig.background.submitError = null
        rig.coordinator.onTap()
        rig.settle()
        assertEquals("app: the tap after the refusal reaches the begin", 1, rig.take.beginCalls)
        assertEquals(
            "app: the accepted begin clears the busy line back to plain",
            listOf(TakenSentences.LEAD, TakenSentences.NOT_CONVERTED, null),
            lines,
        )
        assertEquals("app: the accepted begin shows the recording face", TileState.RECORDING, rig.tile.states.last())
    }

    @Test
    fun `a cut after a refused cut runs one finish and saves and copies its text`() {
        val lines = ArrayList<String?>()
        val rig = refusingRig(lines)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        rig.background.submitError = null
        rig.coordinator.onTakeMicTaken()
        assertEquals("app: the second cut queues exactly one background block", 1, rig.background.pending)
        rig.settle()
        assertEquals("app: the second cut runs exactly one finish", 1, rig.take.finishCalls)
        assertEquals(
            "app: the lines are the refused cut's pair followed by the second cut's pair",
            listOf(TakenSentences.LEAD, TakenSentences.NOT_CONVERTED, TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED),
            lines,
        )
        assertEquals("app: the second cut saves its text once", 1, rig.history.saved.size)
        assertEquals("app: the second cut copies its text once", listOf("captured text"), rig.clipboard.copies)
    }

    @Test
    fun `a send after a refused cut is accepted and runs one finish`() {
        val lines = ArrayList<String?>()
        val rig = refusingRig(lines)
        rig.coordinator.onTakeMicTaken()
        rig.background.submitError = null
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: the send after the refusal runs exactly one finish", 1, rig.take.finishCalls)
        assertEquals(
            "app: the send after the refusal posts no taken line of its own",
            listOf(TakenSentences.LEAD, TakenSentences.NOT_CONVERTED),
            lines,
        )
    }
}
