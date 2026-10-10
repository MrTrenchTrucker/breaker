package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Upper bound on settle passes, so a block that keeps posting itself fails by name instead of hanging. */
private const val MAX_SETTLE_PASSES: Int = 1_000

/**
 * A taken take whose history save fails, or has no history store at all. The taken branch still settles:
 * the clipboard gets the text, the outcome line says the text was copied but not saved, and the branch
 * lets go, so a later begin and a later cancel are accepted. Each test builds its own coordinator, because
 * the shared tile rig takes only a recording history.
 */
class TakeYieldSaveFailedTest {

    @Test
    fun `after a failed save the settle runs once and posts the copied-not-saved line`() {
        val lines = ArrayList<String?>()
        val rig = SaveOutcomeRig(lines, TakeYieldSaveFailedHistory())
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        assertEquals(
            "app: the failed save posts the lead line, then the copied-not-saved line",
            listOf(TakenSentences.LEAD, TakenSentences.COPIED_NOT_SAVED),
            lines,
        )
        assertEquals("app: the taken text is copied once", listOf("captured text"), rig.clipboard.copies)
        assertFalse("app: the taken branch is released after a failed save", rig.coordinator.yield.yieldRunning())
    }

    @Test
    fun `after a failed save a later begin and a later cancel are accepted`() {
        val lines = ArrayList<String?>()
        val rig = SaveOutcomeRig(lines, TakeYieldSaveFailedHistory())
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: the begin after the failed save reaches the take", 1, rig.take.beginCalls)
        rig.coordinator.onCancel()
        assertEquals(
            "app: the cancel after the failed save shows the armed tile",
            TileState.ARMED,
            rig.tile.states.last(),
        )
        rig.settle()
        assertEquals("app: the cancel after the failed save reaches the take", 1, rig.take.cancelCalls)
    }

    @Test
    fun `after a failed save the send flag is cleared so a send tap after the settle sends once`() {
        val lines = ArrayList<String?>()
        val rig = SaveOutcomeRig(lines, TakeYieldSaveFailedHistory())
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: the send tap after the failed save finishes the take once more", 2, rig.take.finishCalls)
        assertEquals("app: the send tap after the failed save sends the take once", 1, rig.take.sendCalls)
    }

    @Test
    fun `a null history store keeps the saved-and-copied line`() {
        val lines = ArrayList<String?>()
        val rig = SaveOutcomeRig(lines, null)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        assertEquals(
            "app: with no history store the settle posts the saved-and-copied line",
            listOf(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED),
            lines,
        )
        assertEquals("app: the taken text is copied once", listOf("captured text"), rig.clipboard.copies)
    }

    @Test
    fun `a working save posts the saved-and-copied line and not the copied-not-saved line`() {
        val lines = ArrayList<String?>()
        val history = RecordingHistory()
        val rig = SaveOutcomeRig(lines, history)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        assertEquals("app: the working save stores the taken text once", 1, history.saved.size)
        assertEquals(
            "app: the working save posts the saved-and-copied line",
            listOf(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED),
            lines,
        )
    }
}

/** A history store whose save always throws, the way a store with no room left would. */
internal class TakeYieldSaveFailedHistory : HistoryStore {
    override fun save(transcription: Transcription) {
        throw IllegalStateException("app: the history store could not save")
    }

    override fun list(limit: Int): List<Transcription> = emptyList()

    override fun delete(id: String): Boolean = false
}

/** The coordinator over the shared fakes, with the history store given by the test (null is allowed). */
private class SaveOutcomeRig(lines: ArrayList<String?>, history: HistoryStore?) {
    val clipboard = RecordingClipboard()
    val tile = RecordingTile()
    val main = ManualMain()
    val background = ManualBackground()
    val take = FakeTake()
    val coordinator = TileCoordinator(
        tile,
        main,
        background,
        take,
        FakeModelReady(true),
        RecordingModelNotice(),
        RecordingOpener(),
        clipboard,
        { lines.add(it) },
        history,
    )

    init {
        coordinator.onArmedChanged(true)
    }

    /** Runs the background and then the main queue until both are empty. */
    fun settle() {
        var passes = 0
        while (background.pending > 0 || main.pending > 0) {
            passes += 1
            check(passes <= MAX_SETTLE_PASSES) {
                "app: the save outcome rig did not settle after $MAX_SETTLE_PASSES passes"
            }
            background.runAll()
            main.drain()
        }
    }
}
