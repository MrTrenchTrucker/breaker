package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A send tap that lands while a taken transcription is still running. The taken take is finished once,
 * by the taken branch. The send tap is refused, so the take is finished and sent no second time.
 * The rig is the shared tile rig: its take counts every finish and send call, and the background and
 * the main queue run only when the test settles them, so a send that was accepted shows up in the
 * counts after the settle.
 */
class TakeYieldSendTapTest {

    @Test
    fun `a send tap during a taken transcription is refused and the take finishes once`() {
        val lines = ArrayList<String?>()
        val rig = TileRig(takenNotice = { lines.add(it) })
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        // The send tap lands before the taken transcription has run or settled.
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: the taken take is finished exactly once", 1, rig.take.finishCalls)
        assertEquals("app: a send tap during a taken transcription sends nothing", 0, rig.take.sendCalls)
        assertEquals("app: the taken text is saved to history once", 1, rig.history.saved.size)
        assertEquals("app: the saved text is the taken text", "captured text", rig.history.saved.first().text)
        assertEquals("app: the taken text is copied once", listOf("captured text"), rig.clipboard.copies)
        assertEquals(
            "app: only the lead line and the saved-and-copied line are posted",
            listOf(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED),
            lines
        )
    }
}
