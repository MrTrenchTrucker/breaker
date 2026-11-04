package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicReleaseWatcher
import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tile face and the watcher's free answer: the busy face that a taken cut brings up is
 * cleared by the watcher's free answer on its own, so the tile repaints to the plain face
 * without another tap, and a second free answer repaints nothing.
 */
internal class TileCoordinatorWatcherFreeTest {

    /**
     * A watcher whose free callback the test holds in a field and calls by hand, so the free
     * reading arrives after the busy face is up.
     */
    private class HandledWatcher : MicReleaseWatcher {
        var free: (() -> Unit)? = null

        override fun start(onFree: () -> Unit) {
            free = onFree
        }

        override fun stop() {
            free = null
        }
    }

    @Test
    fun `the tile face clears when the watcher reports the microphone free`() {
        val watcher = HandledWatcher()
        val lines = ArrayList<String?>()
        val rig = TileRig(takenNotice = { lines.add(it) })
        rig.coordinator.yield.releaseWatch = { watcher }
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        assertEquals("app: the taken cut leaves the tile on the busy face", TileState.MIC_BUSY, rig.tile.states.last())
        assertEquals("app: the taken cut leaves the still taken line and the settle line",
            listOf<String?>(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED), lines)
        // The watcher reports the microphone free after the busy face is up: the tile repaints
        // to the plain face and the still taken line is cleared.
        val freeCallback: () -> Unit = watcher.free!!
        freeCallback()
        assertEquals("app: the free answer repaints the tile to the armed face",
            TileState.ARMED, rig.tile.states.last())
        assertEquals("app: the free answer clears the still taken line",
            listOf<String?>(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED, null), lines)
        // A second free answer is a no-op: no repaint and no new line.
        val callsBefore = rig.tile.calls.size
        freeCallback()
        assertEquals("app: a second free answer repaints nothing", callsBefore, rig.tile.calls.size)
        assertEquals("app: a second free answer adds no second clear line", 3, lines.size)
    }

    /**
     * A free callback that arrives after the switch was turned off is a no-op: the disarm
     * already cleared the busy flag and stopped the watcher, so the late callback finds the
     * flag false, repaints nothing, and the tile is not repainted to the armed face.
     */
    @Test
    fun `a free callback that arrives after the switch off repaints nothing`() {
        val watcher = HandledWatcher()
        val lines = ArrayList<String?>()
        val rig = TileRig(takenNotice = { lines.add(it) })
        rig.coordinator.yield.releaseWatch = { watcher }
        rig.coordinator.onTakeMicTaken()
        rig.settle()
        assertEquals("app: the taken cut leaves the tile on the busy face", TileState.MIC_BUSY, rig.tile.states.last())
        // The disarm stops the watcher (its free slot goes null), so keep the callback before.
        val freeCallback: () -> Unit = watcher.free!!
        rig.coordinator.onArmedChanged(false)
        rig.settle()
        assertEquals("app: the disarm leaves the flag false", false, rig.coordinator.yield.micBusy.get())
        val callsBefore = rig.tile.calls.size
        freeCallback()
        assertEquals("app: the late callback paints no new tile state", callsBefore, rig.tile.calls.size)
        assertEquals("app: the late callback repaints nothing to armed", TileState.IDLE, rig.tile.states.last())
        assertEquals("app: the late callback adds no new line", 3, lines.size)
    }

    /**
     * A refused open that the home path forwards while the tile is not armed: the busy flag comes
     * up and the watcher starts even though the tile is disarmed, and when the watcher's free
     * answer arrives the tile is not repainted at all, because the repaint hook only runs for an
     * armed tile. The home listener still hears both flips, and the notice lines are the
     * refused line and its clear.
     */
    @Test
    fun `a refused open on a disarmed tile repaints nothing when the watcher frees`() {
        val watcher = HandledWatcher()
        val lines = ArrayList<String?>()
        val fired = ArrayList<Boolean>()
        // A rig whose coordinator is never armed: the switch is off from the start.
        val rig = TileRig(arm = false, takenNotice = { lines.add(it) })
        rig.coordinator.yield.releaseWatch = { watcher }
        rig.coordinator.yield.onBusyChanged = { fired.add(it) }
        // The home path forwards the refused open straight to the take-yield controller, with no
        // armed check: the same call the forwarder makes.
        rig.coordinator.yield.beginRefusedAsTaken()
        assertEquals("app: the refused open busy's the flag on a disarmed tile", true, rig.coordinator.yield.micBusy.get())
        assertEquals("app: the flip to busy starts the watcher", true, watcher.free != null)
        assertEquals("app: the listener hears the flip to busy", listOf(true), fired)
        assertEquals("app: the refused line shows on the disarmed face", listOf<String?>(TakenSentences.STILL_TAKEN), lines)
        // Keep the free callback and fire it, the way a late free reading arrives.
        val callsBefore = rig.tile.calls.size
        val freeCallback: () -> Unit = watcher.free!!
        freeCallback()
        assertEquals("app: the free answer clears the flag", false, rig.coordinator.yield.micBusy.get())
        assertEquals("app: the disarmed tile gets no new paint from the free answer", callsBefore, rig.tile.calls.size)
        assertEquals("app: the listener hears exactly the two flips", listOf(true, false), fired)
        assertEquals("app: the free answer clears the refused line",
            listOf<String?>(TakenSentences.STILL_TAKEN, null), lines)
    }
}
