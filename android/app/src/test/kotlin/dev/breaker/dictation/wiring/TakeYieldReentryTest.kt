package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Re-entry and a stale settle of the taken branch. Each test builds a fresh [TileRig]. The first test
 * sends its second cut straight to the taken controller, because the coordinator would drop that cut
 * before the controller saw it. The second test lets a switch-off and a re-arm happen while a take
 * block is still queued.
 */
class TakeYieldReentryTest {

    /** A fresh rig whose taken-notice lambda collects every posted line into [lines]. */
    private fun reentryRig(lines: ArrayList<String?>): TileRig =
        TileRig(takenNotice = { lines.add(it) }, clipboard = RecordingClipboard(), history = RecordingHistory())

    @Test
    fun `a second cut that reaches the taken controller while the first take runs starts no second finish`() {
        val lines = ArrayList<String?>()
        val rig = reentryRig(lines)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        // The coordinator drops a second cut by its own check, before the taken controller sees it.
        // To test the controller's own check, the second cut is sent to the controller directly.
        val tileCallsBefore: Int = rig.tile.calls.size
        rig.coordinator.yield.onTakeMicTaken()
        assertEquals("app: the second cut queues no second take block", 1, rig.background.pending)
        assertEquals("app: the second cut posts no second lead line", listOf(TakenSentences.LEAD), lines)
        assertEquals("app: the second cut pushes no tile state", tileCallsBefore, rig.tile.calls.size)
        rig.settle()
        assertEquals("app: the take was finished once", 1, rig.take.finishCalls)
        assertEquals("app: the transcription was saved once", 1, rig.history.saved.size)
        assertEquals("app: the text was copied once", listOf("captured text"), rig.clipboard.copies)
        assertEquals(
            "app: the settle posted the saved-and-copied line",
            TakenSentences.SAVED_AND_COPIED,
            lines.last(),
        )
    }

    @Test
    fun `a settle left from before a switch off and re-arm posts no line, copies nothing and pushes no state`() {
        val lines = ArrayList<String?>()
        val rig = reentryRig(lines)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        // The take block of the cut is queued and has not run yet. The switch goes off and then on again.
        // The switch-off clears the controller's running flag, but the queued block has already passed
        // the one-yield check, so it still runs its finish and reaches the settle check, which refuses it.
        // The background save is not guarded by the switch generation; this test does not assert on it.
        rig.coordinator.onArmedChanged(false)
        rig.coordinator.onArmedChanged(true)
        val statesBefore: Int = rig.tile.states.size
        val lastStateBefore = rig.tile.states.last()
        rig.settle()
        assertEquals("app: the stale take still ran its finish", 1, rig.take.finishCalls)
        assertEquals("app: the stale settle posts no outcome line", listOf(TakenSentences.LEAD, null), lines)
        assertEquals("app: the stale settle copies nothing", emptyList<String>(), rig.clipboard.copies)
        assertEquals("app: the stale settle pushes no tile state", statesBefore, rig.tile.states.size)
        assertEquals("app: the tile keeps the state of the new session", lastStateBefore, rig.tile.states.last())
    }
}
