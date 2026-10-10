package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The taken-entry path, driven at the coordinator level over a fresh TileRig each time. These replace
 * the broken runner of the same name (it referenced a non-existent `TakenSentines`, asserted the MODEL
 * notice instead of the taken-notice lambda, and poked the private `tile.pushed`). Every test drives one
 * coordinator call (or settle) against a clean armed tile; to record the taken line each rig is built with
 * its own notice-lambda collector. The one helper below returns such a rig.
 */
class TileCoordinatorTakenTest {

    /** A fresh rig whose taken-notice lambda collects every posted line into [lines]. */
    private fun rigWithLines(lines: ArrayList<String?>): TileRig {
        return TileRig(takenNotice = { lines.add(it) }, clipboard = RecordingClipboard(), history = RecordingHistory())
    }

    @Test
    fun `a take cut by the microphone ends with the busy face and the line`() {
        val lines = ArrayList<String?>()
        val rig = rigWithLines(lines)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        // At the cut the taken line is the LEAD sentence.
        assertEquals(listOf(TakenSentences.LEAD), lines)
        // The face maps to MIC_BUSY through the micBusy clause.
        assertEquals(TileState.MIC_BUSY, rig.tile.states.last())
        // The tile's own notices stay empty on the taken path (no notice text posted there).
        assertEquals(emptyList<String>(), rig.tile.notices)
        // Background queued exactly one block; nothing runs inline or posts to main yet.
        assert(rig.background.pending == 1) { "the block queues on the background" }
        assert(rig.main.pending == 0) { "nothing reaches main until the background runs" }
        rig.settle()
        // The settle posts the saved-and-copied line (LEAD replaced), history saved once, clipboard copied once.
        assertEquals(TakenSentences.SAVED_AND_COPIED, lines.last())
        assertEquals(1, rig.history.saved.size)
        assertEquals(listOf("captured text"), rig.clipboard.copies)
        // After the settle the session reset ran back to IDLE and a follow-up begin is accepted again.
        assert(rig.take.sessionState == DictationState.IDLE) { "the reset ran: session back to IDLE" }
        rig.coordinator.onBegin()
        rig.settle()
        assert(rig.take.beginCalls >= 1) { "a begin after the settle reaches the take" }
    }

    @Test
    fun `a taken take with no text saves nothing and copies nothing`() {
        // (a) a blank ReadyToSend: the transcription's text is whitespace, so it saves/copies nothing.
        val linesA = ArrayList<String?>()
        val a = rigWithLines(linesA)
        a.take.finishResult = FinishResult.ReadyToSend(tileTranscription("   "))  // blank
        a.coordinator.onTakeMicTaken()
        a.settle()
        assertEquals(TakenSentences.TAKEN_NOTHING_HEARD, linesA.last())
        assertEquals(0, a.history.saved.size)
        assertEquals(emptyList<String>(), a.clipboard.copies)

        // (b) the empty-take Failed sentence answers the second nothing-heard arm.
        val linesB = ArrayList<String?>()
        val b = rigWithLines(linesB)
        b.take.finishResult = FinishResult.Failed("No audio was captured")
        b.coordinator.onTakeMicTaken()
        b.settle()
        assertEquals(TakenSentences.TAKEN_NOTHING_HEARD, linesB.last())
        assertEquals(0, b.history.saved.size)
        assertEquals(emptyList<String>(), b.clipboard.copies)
    }

    @Test
    fun `a taken take that cannot be converted posts the not-converted line and never the detail`() {
        // (a) a finish that throws: the coordinator drops it to the not-converted line; the exception text must not reach the line.
        val linesA = ArrayList<String?>()
        val a = rigWithLines(linesA)
        a.take.finishError = RuntimeException("the engine died")
        a.coordinator.onTakeMicTaken()
        a.settle()
        assertEquals(TakenSentences.NOT_CONVERTED, linesA.last())
        assert(!linesA.any { it == "the engine died" }) { "thrown detail must never reach the taken line" }

        // (b) a non-empty Failed conversion sentence also answers the not-converted line.
        val linesB = ArrayList<String?>()
        val b = rigWithLines(linesB)
        b.take.finishResult = FinishResult.Failed("The speech could not be converted.")
        b.coordinator.onTakeMicTaken()
        b.settle()
        assertEquals(TakenSentences.NOT_CONVERTED, linesB.last())
        assert(!linesB.any { it == "The speech could not be converted." }) { "detail must never reach the taken line" }
    }

    @Test
    fun `a tap on the busy face reaches the begin and not the launcher`() {
        val rig = TileRig()
        assert(rig.tile.states.all { it != TileState.MIC_BUSY }) { "fresh tile is armed, not busy" }
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        assertEquals(TileState.MIC_BUSY, rig.tile.states.last())
        rig.coordinator.onTap()
        // The busy-face tap routes to the begin; settle runs the queued answer before the asserts.
        rig.settle()
        // A tap on the busy face re-enters the begin: beginCalls rises, nothing routes to the launcher.
        assert(rig.take.beginCalls >= 1) { "the busy-face tap reaches the begin" }
        assert(rig.opener.routes.all { it == null }) { "a busy-face tap does not open the launcher" }
    }

    @Test
    fun `the busy face stays settled and a begin after starts clean`() {
        val lines = ArrayList<String?>()
        val rig = rigWithLines(lines)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        assert(rig.tile.states.last() == TileState.MIC_BUSY) { "the face is busy at the cut" }
        rig.settle()
        // The settle leaves the face MIC_BUSY (no ARMED push).
        assertEquals(TileState.MIC_BUSY, rig.tile.states.last())
        assert(rig.take.sessionState == DictationState.IDLE) { "the reset ran: session back to IDLE" }
        // A begin after the taken take clears the busy face back to armed.
        rig.coordinator.onBegin()
        rig.settle()
        assert(rig.tile.states.last() != TileState.MIC_BUSY) { "a post-settle begin leaves nothing busy" }
    }

    @Test
    fun `a take refused as taken shows the busy face and the refused-open line`() {
        val lines = ArrayList<String?>()
        val rig = rigWithLines(lines)
        rig.take.beginResult = BeginResult.Taken
        rig.coordinator.onBegin()
        // The begin-refused-as-taken answer runs on the queues; settle applies it before the asserts.
        rig.settle()
        assert(rig.tile.states.last() == TileState.MIC_BUSY) { "the face is busy" }
        assertEquals(listOf(TakenSentences.STILL_TAKEN), lines)
    }

    @Test
    fun `a tap in IDLE opens the launcher and does not begin`() {
        val rig = TileRig()
        rig.coordinator.onTap()
        // A tap on the armed face routes to the launcher, never begins a take.
        assert(rig.opener.routes.any { it == null }) { "an IDLE tap opens the launcher" }
        assertEquals(0, rig.take.beginCalls)
    }

    @Test
    fun `a cancel during a taken transcription is refused and leaves history alone`() {
        val lines = ArrayList<String?>()
        val rig = rigWithLines(lines)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        val before = rig.clipboard.copies.size + rig.history.saved.size
        rig.coordinator.onCancel()
        // The guard refuses cancel while the taken branch runs (sendPushed).
        assertEquals(0, rig.take.cancelCalls)
        assertEquals(before, rig.clipboard.copies.size + rig.history.saved.size)
    }

    @Test
    fun `a send during a taken transcription is refused and types nothing`() {
        val lines = ArrayList<String?>()
        val rig = rigWithLines(lines)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        val before = rig.take.sendCalls
        rig.coordinator.onSend()
        assertEquals(before, rig.take.sendCalls)  // send guard refuses while the taken branch runs
    }

    @Test
    fun `a second cut during the settle is dropped`() {
        val lines = ArrayList<String?>()
        val rig = rigWithLines(lines)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        // A second onTakeMicTaken before the settle is dropped by the yieldRunning guard.
        rig.coordinator.onTakeMicTaken()
        assert(rig.background.pending == 1) { "exactly one block queued; no second runs inline" }
        assert(rig.main.pending == 0) { "nothing posts to main until the background runs" }
        // A third cut is also dropped: still exactly one queued block.
        rig.coordinator.onTakeMicTaken()
        assertEquals(1, rig.background.pending)
        rig.settle()
        assert(rig.take.finishCalls == 1) { "the block ran once" }
        assertEquals(TakenSentences.SAVED_AND_COPIED, lines.last())
    }

    @Test
    fun `a take end after a taken take restores the plain line and a begin after it clears the busy face`() {
        val lines = ArrayList<String?>()
        val rig = rigWithLines(lines)
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        // The busy face is settled (the cut's settle kept it); the line is the outcome.
        assertEquals(TileState.MIC_BUSY, rig.tile.states.last())
        // A normal (non-taken) take end: the guard now passes (the settle cleared sendPushed); it pushes, then clears the flag and the line.
        rig.coordinator.onTakeEnded()
        // The push onTakeEnded causes still answers the flag (the clear is after it), so the face flips at the NEXT push - a follow-up begin.
        assertEquals(null, lines.last())  // the plain line is restored
        rig.coordinator.onBegin()
        rig.settle()
        // The begin (the next push) runs with the flag cleared: the face is no longer the busy face.
        assert(rig.tile.states.last() != TileState.MIC_BUSY) { "the cleared flag leaves the busy face at the next push" }
        // The taken line list is exactly LEAD, the saved-and-copied line (the default take's outcome), then a null plain line from the
        // take end, then a second null from the begin the test makes (the accepted begin restores the plain line).
        assertEquals(listOf(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED, null, null), lines)
    }
}
