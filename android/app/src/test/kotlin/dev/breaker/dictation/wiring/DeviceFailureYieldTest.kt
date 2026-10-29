package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.usecase.SendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A take that counts its calls. Finish answers [finishResult], or throws [finishError] when it is set. */
private class DeviceTake : TakePort {
    override var sessionState: DictationState = DictationState.RECORDING
    var finishResult: FinishResult = FinishResult.ReadyToSend(tileTranscription("captured text"))
    var finishError: RuntimeException? = null
    var finishCalls: Int = 0
    var resetCalls: Int = 0
    var sendCalls: Int = 0
    var cancelCalls: Int = 0

    override fun begin(): BeginResult = BeginResult.Recording

    override fun finish(trimBeforeMs: Long?): FinishResult {
        finishCalls += 1
        finishError?.let { throw it }
        return finishResult
    }

    override fun send(): SendResult? {
        sendCalls += 1
        return null
    }

    override fun cancel() {
        cancelCalls += 1
    }

    override fun resetAfterTaken() {
        resetCalls += 1
        sessionState = DictationState.IDLE
    }
}

/** The yield over fakes: [settled] collects every line the settle answers with, [generation] is the switch count. */
private class YieldRig(val history: HistoryStore?, refuse: Boolean = false) {
    val take = DeviceTake()
    val main = ManualMain()
    val background = ManualBackground()
    val clipboard = RecordingClipboard()
    val settled = ArrayList<String>()
    var generation: Int = 0
    val yield = DeviceFailureYield(background, main, take, TakeSettle(history, clipboard), { generation }) { settled.add(it) }

    init {
        if (refuse) background.submitError = RuntimeException("the background refused the work")
    }

    /** Runs the background and then the main queue until both are empty. */
    fun settle() {
        while (background.pending > 0 || main.pending > 0) {
            background.runAll()
            main.drain()
        }
    }
}

internal class DeviceFailureYieldTest {

    @Test
    fun `a device failure with audio saves and copies the text and settles with the saved and copied line`() {
        val history = RecordingHistory()
        val rig = YieldRig(history)
        assertTrue("yield: the start is queued", rig.yield.start())
        rig.settle()
        assertEquals("yield: the settle answers the saved and copied line", listOf(DeviceFailureSentences.SAVED_AND_COPIED), rig.settled)
        assertEquals("yield: the text is stored once", 1, history.saved.size)
        assertEquals("yield: the stored text is the captured text", "captured text", history.saved.first().text)
        assertEquals("yield: the text is copied once", listOf("captured text"), rig.clipboard.copies)
        assertEquals("yield: the take is finished once", 1, rig.take.finishCalls)
        assertEquals("yield: the take is reset once after the finish", 1, rig.take.resetCalls)
        assertFalse("yield: the running flag is clear after the answer", rig.yield.running())
    }

    @Test
    fun `a device failure whose save throws still copies the text and settles with the copied not saved line`() {
        val rig = YieldRig(TakeYieldSaveFailedHistory())
        rig.yield.start()
        rig.settle()
        assertEquals("yield: a failed save answers the copied not saved line", listOf(DeviceFailureSentences.COPIED_NOT_SAVED), rig.settled)
        assertEquals("yield: a failed save still copies the text", listOf("captured text"), rig.clipboard.copies)
    }

    @Test
    fun `a device failure with blank text saves nothing and settles with the nothing heard line`() {
        val history = RecordingHistory()
        val rig = YieldRig(history)
        rig.take.finishResult = FinishResult.ReadyToSend(tileTranscription("  "))
        rig.yield.start()
        rig.settle()
        assertEquals("yield: a blank text answers the nothing heard line", listOf(DeviceFailureSentences.NOTHING_HEARD), rig.settled)
        assertEquals("yield: a blank text is not stored", 0, history.saved.size)
        assertEquals("yield: a blank text is not copied", emptyList<String>(), rig.clipboard.copies)
    }

    @Test
    fun `a device failure with no audio gives the lead sentence only`() {
        val history = RecordingHistory()
        val rig = YieldRig(history)
        rig.take.finishResult = FinishResult.Failed(EMPTY_TAKE_SENTENCE)
        rig.yield.start()
        rig.settle()
        assertEquals("yield: an empty take answers the lead sentence only", listOf(DeviceFailureSentences.LEAD), rig.settled)
        assertEquals("yield: an empty take is not stored", 0, history.saved.size)
        assertEquals("yield: an empty take is not copied", emptyList<String>(), rig.clipboard.copies)
    }

    @Test
    fun `another failed finish answers the not converted line and a throwing finish never shows its text`() {
        val other = YieldRig(RecordingHistory())
        other.take.finishResult = FinishResult.Failed("The speech could not be converted.")
        other.yield.start()
        other.settle()
        assertEquals("yield: another failure answers the not converted line", listOf(DeviceFailureSentences.NOT_CONVERTED), other.settled)
        val thrown = YieldRig(RecordingHistory())
        thrown.take.finishError = IllegalStateException("the engine died")
        thrown.yield.start()
        thrown.settle()
        assertEquals("yield: a throwing finish answers the not converted line", listOf(DeviceFailureSentences.NOT_CONVERTED), thrown.settled)
        assertFalse("yield: the engine text never reaches the line", thrown.settled.any { it.contains("engine died") })
    }

    @Test
    fun `a settle left from before a switch off posts no line and copies nothing`() {
        val history = RecordingHistory()
        val rig = YieldRig(history)
        rig.yield.start()
        rig.generation += 1
        rig.settle()
        assertEquals("yield: a stale settle posts no line", emptyList<String>(), rig.settled)
        assertEquals("yield: a stale settle copies nothing", emptyList<String>(), rig.clipboard.copies)
        assertFalse("yield: a stale settle still clears the running flag", rig.yield.running())
    }

    @Test
    fun `a refusing background makes start return false and calls no reset and leaves the session recording`() {
        val rig = YieldRig(RecordingHistory(), refuse = true)
        assertFalse("yield: a refused start returns false", rig.yield.start())
        assertEquals("yield: a refused start calls no reset", 0, rig.take.resetCalls)
        assertEquals("yield: a refused start leaves the session recording", DictationState.RECORDING, rig.take.sessionState)
        assertEquals("yield: a refused start does not finish", 0, rig.take.finishCalls)
        assertFalse("yield: a refused start clears the running flag", rig.yield.running())
        assertEquals("yield: a refused start answers nothing", emptyList<String>(), rig.settled)
        rig.background.submitError = null
        assertTrue("yield: a later start is accepted once the background works", rig.yield.start())
    }

    @Test
    fun `a second start while one is running returns false and queues nothing more`() {
        val rig = YieldRig(RecordingHistory())
        assertTrue("yield: the first start is queued", rig.yield.start())
        assertFalse("yield: a second start while running returns false", rig.yield.start())
        assertEquals("yield: a second start queues no block", 1, rig.background.pending)
    }

    @Test
    fun `a device failure never sends the text into the field`() {
        val rig = YieldRig(RecordingHistory())
        rig.yield.start()
        rig.settle()
        assertEquals("yield: the text is never sent to the field", 0, rig.take.sendCalls)
        assertEquals("yield: nothing is cancelled either", 0, rig.take.cancelCalls)
    }

    @Test
    fun `a stale run after a switch off leaves the running flag of the later run alone`() {
        val backgroundSteps = DeviceStepQueue()
        val mainSteps = DeviceStepQueue()
        val settled = ArrayList<String>()
        var generation = 0
        val yield = DeviceFailureYield(backgroundSteps, mainSteps, DeviceTake(), TakeSettle(RecordingHistory(), RecordingClipboard()), { generation }) { settled.add(it) }
        assertTrue("yield: the first start is queued", yield.start())
        yield.clear()
        generation += 1
        assertTrue("yield: the second start after the switch off is queued", yield.start())
        backgroundSteps.runNext()
        mainSteps.runNext()
        assertTrue("yield: the stale run does not clear the running flag of the later run", yield.running())
        assertEquals("yield: the stale run answers no line", emptyList<String>(), settled)
        backgroundSteps.runNext()
        assertTrue("yield: the later run is still running before its answer", yield.running())
        mainSteps.runNext()
        assertFalse("yield: the later run clears the running flag on its answer", yield.running())
        assertEquals("yield: the later run answers the saved and copied line once", listOf(DeviceFailureSentences.SAVED_AND_COPIED), settled)
    }
}

/** Queues the blocks it is given and runs them one at a time, oldest first, on the calling thread. */
private class DeviceStepQueue : MainPost, Background {
    private val blocks: MutableList<() -> Unit> = ArrayList()

    override fun post(block: () -> Unit) {
        blocks.add(block)
    }

    override fun submit(block: () -> Unit) {
        blocks.add(block)
    }

    /** Runs the oldest queued block; fails by name when none is queued. */
    fun runNext() {
        check(blocks.isNotEmpty()) { "yield: no queued block to run" }
        blocks.removeAt(0).invoke()
    }
}
