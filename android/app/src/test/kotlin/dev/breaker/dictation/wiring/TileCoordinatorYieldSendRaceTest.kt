package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A background queue that runs one submitted block at a time, on demand. */
internal class StepBackground : Background {
    private val queue: MutableList<() -> Unit> = ArrayList()
    val pending: Int get() = queue.size
    var submitError: RuntimeException? = null
    override fun submit(block: () -> Unit) {
        submitError?.let { throw it }
        queue.add(block)
    }
    fun runOne() { if (queue.isNotEmpty()) queue.removeAt(0)() }
    fun runAll() { while (queue.isNotEmpty()) runOne() }
}

/** A main queue that runs one posted block at a time, on demand. */
internal class StepMain : MainPost {
    private val queue: MutableList<() -> Unit> = ArrayList()
    val pending: Int get() = queue.size
    override fun post(block: () -> Unit) { queue.add(block) }
    fun runOne() { if (queue.isNotEmpty()) queue.removeAt(0)() }
    fun runAll() { while (queue.isNotEmpty()) runOne() }
}

/**
 * A microphone take that arrives while a begin is pending is held until the begin settles;
 * a refused begin must not let a send start while the yield runs.
 */
class TileCoordinatorYieldSendRaceTest {

    private data class Rig(
        val tile: RecordingTile,
        val main: StepMain,
        val background: StepBackground,
        val take: FakeTake,
        val coordinator: TileCoordinator,
    )

    private fun rig(beginResult: BeginResult): Rig {
        val tile = RecordingTile()
        val main = StepMain()
        val background = StepBackground()
        val take = FakeTake()
        take.beginResult = beginResult
        val model = FakeModelReady(true)
        val notice = RecordingModelNotice()
        val opener = RecordingOpener()
        val clipboard = RecordingClipboard()
        val history = RecordingHistory()
        val coord = TileCoordinator(tile, main, background, take, model, notice, opener, clipboard, {}, history)
        coord.onArmedChanged(true)
        return Rig(tile, main, background, take, coord)
    }

    private fun Rig.drain() {
        while (background.pending > 0 || main.pending > 0) {
            background.runAll()
            main.runAll()
        }
    }

    @Test
    fun `a send is refused while the microphone yield runs`() {
        val r = rig(BeginResult.Refused("busy"))
        r.coordinator.onBegin()
        r.coordinator.onTakeMicTaken()
        r.background.runAll()
        r.main.runOne()
        val beforeSendCalls = r.take.sendCalls
        r.coordinator.onSend()
        r.drain()

        assertEquals("no send started", beforeSendCalls, r.take.sendCalls)
        assertFalse("tile never showed SENDING", r.tile.states.contains(TileState.SENDING))
    }

    @Test
    fun `a mic take during a pending begin starts the yield only after the begin settles`() {
        val r = rig(BeginResult.Recording)
        r.coordinator.onBegin()
        r.coordinator.onTakeMicTaken()
        assertFalse("yield not running before settle", r.coordinator.yield.yieldRunning())
        r.background.runOne()
        r.main.runOne()
        assertTrue("yield running after settle (replayed)", r.coordinator.yield.yieldRunning())
    }

    @Test
    fun `a taken begin drops the held mic take with no second yield`() {
        val r = rig(BeginResult.Taken)
        r.coordinator.onBegin()
        r.coordinator.onTakeMicTaken()
        r.drain()

        assertEquals("no finish on taken begin", 0, r.take.finishCalls)
        assertFalse("no yield running", r.coordinator.yield.yieldRunning())
        // stale-take scenario: a second begin to Recording must not replay the dropped take
        r.take.beginResult = BeginResult.Recording
        r.coordinator.onBegin()
        r.drain()
        assertEquals("no finish on stale taken settle", 0, r.take.finishCalls)
    }

    @Test
    fun `a failed begin shows the sentence and does not replay`() {
        val r = rig(BeginResult.Failed("boom"))
        r.coordinator.onBegin()
        r.coordinator.onTakeMicTaken()
        r.drain()

        assertEquals("no finish on failed begin", 0, r.take.finishCalls)
        assertFalse("no yield running", r.coordinator.yield.yieldRunning())
        assertTrue("tile showed a notice", r.tile.notices.isNotEmpty())
        // stale-take scenario: a second begin to Recording must not replay the dropped take
        r.coordinator.onTap()
        r.take.beginResult = BeginResult.Recording
        r.coordinator.onBegin()
        r.drain()
        assertEquals("no finish on stale failed settle", 0, r.take.finishCalls)
    }

    @Test
    fun `cancel while a begin is pending drops the held take`() {
        val r = rig(BeginResult.Recording)
        r.coordinator.onBegin()
        r.coordinator.onTakeMicTaken()
        r.coordinator.onCancel()
        r.drain()

        assertEquals("no finish after cancel", 0, r.take.finishCalls)
    }

    @Test
    fun `disarm while a begin is pending drops the held take`() {
        val r = rig(BeginResult.Recording)
        r.coordinator.onBegin()
        r.coordinator.onTakeMicTaken()
        r.coordinator.onArmedChanged(false)
        r.coordinator.onArmedChanged(true)
        r.coordinator.onBegin()
        r.drain()

        assertEquals("no finish after disarm", 0, r.take.finishCalls)
    }
}
