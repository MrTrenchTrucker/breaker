package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clear points of the taken entry, driven at the coordinator level over a fresh TileRig each time.
 * Each test cuts a taken take, lets the cut settle, and then uses one of the ways the busy face and the
 * taken line are cleared: a take end that did not come from a taken take, and a switch off followed by a
 * switch on. The face is read from the tile's pushed states and the taken line from the notice lambda.
 */
class TakeYieldClearPointsTest {

    /** A rig whose taken-notice lambda collects every posted line into [lines], after one taken take has settled. */
    private fun settledTakenRig(lines: ArrayList<String?>): TileRig {
        val rig = TileRig(takenNotice = { lines.add(it) }, clipboard = RecordingClipboard(), history = RecordingHistory())
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        assertEquals("app: the cut settles on the busy face", TileState.MIC_BUSY, rig.tile.states.last())
        assertEquals(
            "app: the cut posts the lead line and then the saved line",
            listOf(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED),
            lines,
        )
        return rig
    }

    @Test
    fun `a take end with no begin after it clears the busy face at the next push`() {
        val lines = ArrayList<String?>()
        val rig = settledTakenRig(lines)
        // A take end that is not a taken one: no begin follows it.
        rig.coordinator.onTakeEnded()
        assertNull("app: the take end posts the plain line", lines.last())
        // Any push without a begin shows the face from the flag: switching on while already on pushes once more.
        rig.coordinator.onArmedChanged(true)
        assertEquals(
            "app: the push after a take end with no begin shows the armed face, not the busy face",
            TileState.ARMED,
            rig.tile.states.last(),
        )
    }

    @Test
    fun `a switch off clears the busy face so the next switch on shows the armed face`() {
        val lines = ArrayList<String?>()
        val rig = settledTakenRig(lines)
        rig.coordinator.onArmedChanged(false)
        assertEquals("app: the switch off shows the idle face", TileState.IDLE, rig.tile.states.last())
        rig.coordinator.onArmedChanged(true)
        assertEquals(
            "app: the switch on after a switch off shows the armed face, not the busy face",
            TileState.ARMED,
            rig.tile.states.last(),
        )
    }

    @Test
    fun `a switch off posts the plain line after the taken lines`() {
        val lines = ArrayList<String?>()
        val rig = settledTakenRig(lines)
        rig.coordinator.onArmedChanged(false)
        assertEquals(
            "app: the switch off posts the plain line as the last taken-line change",
            listOf(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED, null),
            lines,
        )
    }
}
