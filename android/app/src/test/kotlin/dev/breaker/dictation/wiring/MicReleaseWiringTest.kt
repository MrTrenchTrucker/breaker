package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicReleaseWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The busy flag's release watcher: a flip to busy starts the one watcher, the watcher's free answer
 * clears the flag through the same set path, and every clear of the flag by any path stops it, so a
 * fake watcher that counts every start and every stop sees them equal on every path. The face
 * clears by itself when the watcher reports free, stays while the watcher is silent, and a first
 * reading that is already free leaves no taken line behind. The reading the watcher cannot make
 * (an unreadable list or an in-call mode) is tested on the audio side, where the reading lives.
 */
internal class MicReleaseWiringTest {

    /**
     * A [MicReleaseWatcher] the test drives by hand: it counts every start call and every stop call
     * and never swallows one, so a mutant that starts the watcher on a set that is not a flip, or
     * stops it on one that is, shows up in the raw counts. The production code calls it at most
     * once per flip, so the counts stay equal on every path.
     */
    private class CountingWatcher : MicReleaseWatcher {
        var starts = 0
        var stops = 0
        var free: (() -> Unit)? = null

        override fun start(onFree: () -> Unit) {
            starts += 1
            free = onFree
        }

        override fun stop() {
            stops += 1
            free = null
        }
    }

    /** A take-yield controller over the shared fakes, wired to the given watcher. */
    private fun controller(watcher: MicReleaseWatcher): TakeYieldController {
        val log = ArrayList<String>()
        val take = FakeHomeTake(committer = CountingCommitter(), log = log)
        val settle = TakeSettle(InMemoryHistoryStore(log), FakeTakenClipboard())
        return TakeYieldController(
            background = FakeBackground(),
            main = FakeMainPost(),
            take = take,
            settle = settle,
            takenNotice = { },
            pushState = { },
            onSettled = { },
            generation = { 0 },
        ).apply { releaseWatch = { watcher } }
    }

    /**
     * The controller brings the busy face up, the fake watcher reports the microphone free, and the
     * face clears by itself through the same set path, so the one listener hears the flip to false
     * and the stop comes with the clear.
     */
    @Test
    fun `the busy face clears by itself when the watcher reports the microphone free`() {
        val watcher = CountingWatcher()
        val fired = ArrayList<Boolean>()
        val ctrl = controller(watcher).apply { onBusyChanged = { fired.add(it) } }
        ctrl.onTakeMicTaken()
        assertEquals("wiring: the cut brings up the busy face", true, ctrl.micBusy.get())
        assertEquals("wiring: the flip to busy starts the watcher", 1, watcher.starts)
        assertEquals("wiring: the cut has not cleared yet", listOf(true), fired)
        watcher.free?.invoke()
        assertEquals("wiring: the free answer clears the flag", false, ctrl.micBusy.get())
        assertEquals("wiring: the listener hears the flip to false", listOf(true, false), fired)
        assertEquals("wiring: the clear stops the watcher", 1, watcher.stops)
    }

    /**
     * A silent watcher never reports free, so the face stays busy after a taken cut until
     * something else clears it.
     */
    @Test
    fun `a silent watcher after a taken cut leaves the face busy until an accepted begin`() {
        val watcher = CountingWatcher()
        val ctrl = controller(watcher)
        ctrl.onTakeMicTaken()
        assertEquals("wiring: the cut brings up the busy face", true, ctrl.micBusy.get())
        assertEquals("wiring: the flip to busy starts the watcher", 1, watcher.starts)
        // The watcher never reports free: the face stays busy.
        assertEquals("wiring: the face stays busy while the watcher is silent", true, ctrl.micBusy.get())
        ctrl.onBeginAccepted()
        assertEquals("wiring: an accepted begin clears the face", false, ctrl.micBusy.get())
        assertEquals("wiring: the clear stops the watcher", 1, watcher.stops)
    }

    /**
     * The watcher reads the list once at start and that list is already free, so the face clears
     * at once, before the flip to busy is ever reported to the listener.
     */
    @Test
    fun `an already free list clears the busy face at once when the watcher starts`() {
        val fired = ArrayList<Boolean>()
        val eager = object : MicReleaseWatcher {
            override fun start(onFree: () -> Unit) {
                // The one read at start is already free: report free at once.
                onFree()
            }

            override fun stop() {
                // Nothing to unregister in the test.
            }
        }
        val ctrl = controller(eager).apply { onBusyChanged = { fired.add(it) } }
        ctrl.onTakeMicTaken()
        assertEquals("wiring: the already free list cleared the face at once", false, ctrl.micBusy.get())
        assertEquals("wiring: the listener hears only the flip to false", listOf(false), fired)
    }

    /**
     * Every clear of the busy flag, by any path, stops the watcher, so a fake watcher that counts
     * starts and stops sees them equal on every path, and there is no watcher left started.
     */
    @Test
    fun `every clear of the busy flag stops the watcher and the counts stay equal`() {
        // A refused open busy's the face, a take end clears it: one start, one stop.
        val refused = CountingWatcher()
        val refusedCtrl = controller(refused)
        refusedCtrl.beginRefusedAsTaken()
        refusedCtrl.onTakeEndedCleared()
        assertEquals("wiring: a take end starts the watcher once", 1, refused.starts)
        assertEquals("wiring: a take end stops the watcher once", 1, refused.stops)

        // A taken cut busy's the face, a switch off clears it: one start, one stop.
        val disarm = CountingWatcher()
        val disarmCtrl = controller(disarm)
        disarmCtrl.onTakeMicTaken()
        disarmCtrl.disarmCleared()
        assertEquals("wiring: a disarm starts the watcher once", 1, disarm.starts)
        assertEquals("wiring: a disarm stops the watcher once", 1, disarm.stops)

        // An accepted begin clears the face: one start, one stop.
        val accepted = CountingWatcher()
        val acceptedCtrl = controller(accepted)
        acceptedCtrl.onTakeMicTaken()
        acceptedCtrl.onBeginAccepted()
        assertEquals("wiring: an accepted begin starts the watcher once", 1, accepted.starts)
        assertEquals("wiring: an accepted begin stops the watcher once", 1, accepted.stops)

        // A failed begin clears the face: one start, one stop.
        val failed = CountingWatcher()
        val failedCtrl = controller(failed)
        failedCtrl.onTakeMicTaken()
        failedCtrl.onBeginFailed()
        assertEquals("wiring: a failed begin starts the watcher once", 1, failed.starts)
        assertEquals("wiring: a failed begin stops the watcher once", 1, failed.stops)
    }

    /** A second flip to busy while already busy does not start a second watcher. */
    @Test
    fun `a second flip to busy does not start a second watcher`() {
        val watcher = CountingWatcher()
        val ctrl = controller(watcher)
        ctrl.beginRefusedAsTaken()
        ctrl.beginRefusedAsTaken()
        assertEquals("wiring: the second refused open starts no second watcher", 1, watcher.starts)
        ctrl.onTakeMicTaken()
        assertEquals("wiring: a taken cut while busy starts no second watcher", 1, watcher.starts)
        ctrl.onBeginAccepted()
        assertEquals("wiring: the clear after two busy paths stops the watcher once", 1, watcher.stops)
    }

    /**
     * A silent watcher never reports free, so the face stays busy after a refused open, and the
     * accepted begin still clears it the old way.
     */
    @Test
    fun `a silent watcher after a refused open leaves the face busy until an accepted begin`() {
        val watcher = CountingWatcher()
        val ctrl = controller(watcher)
        ctrl.beginRefusedAsTaken()
        assertEquals("wiring: the refused open brings up the busy face", true, ctrl.micBusy.get())
        // The watcher never reports free: the face stays busy.
        assertEquals("wiring: the face stays busy while the watcher is silent", true, ctrl.micBusy.get())
        ctrl.onBeginAccepted()
        assertEquals("wiring: the accepted begin still clears the face", false, ctrl.micBusy.get())
        assertEquals("wiring: the accepted begin stops the watcher", 1, watcher.stops)
    }

    /**
     * A first reading that is already free clears the face at once, and the cut shows no taken
     * line: the first line and the SENDING state stay away. The watcher's clear is a null, and the
     * settle's outcome line follows it, so the face is clear and only the settle line shows
     * (the fake background and main post run at once).
     */
    @Test
    fun `a first reading that is already free leaves the face clear and only the settle line shows`() {
        val lines = ArrayList<String?>()
        val states = ArrayList<String>()
        val eager = object : MicReleaseWatcher {
            override fun start(onFree: () -> Unit) {
                // The one read at start is already free: report free at once.
                onFree()
            }

            override fun stop() {
                // Nothing to unregister in the test.
            }
        }
        val log = ArrayList<String>()
        val take = FakeHomeTake(committer = CountingCommitter(), log = log)
        val settle = TakeSettle(InMemoryHistoryStore(log), FakeTakenClipboard())
        val ctrl = TakeYieldController(
            background = FakeBackground(),
            main = FakeMainPost(),
            take = take,
            settle = settle,
            takenNotice = { lines.add(it) },
            pushState = { states.add(it.name) },
            onSettled = { },
            generation = { 0 },
        ).apply { releaseWatch = { eager } }
        ctrl.onTakeMicTaken()
        assertEquals("wiring: the eager first reading cleared the face at once", false, ctrl.micBusy.get())
        assertEquals("wiring: the cut shows the watcher clear and the settle line, no taken line",
            listOf<String?>(null, TakenSentences.SAVED_AND_COPIED), lines)
        assertEquals("wiring: the cut pushes no SENDING state, only the settle IDLE",
            listOf("IDLE"), states)
    }

    /**
     * A late free reading, one that arrives after the busy face is up, calls the repaint hook
     * exactly once and clears the notice line, and a second free callback does nothing, so the
     * hook never fires twice.
     */
    @Test
    fun `a late free reading repaints the tile once and clears the notice line`() {
        val cleared = ArrayList<Boolean>()
        val lines = ArrayList<String?>()
        val watcher = CountingWatcher()
        val log = ArrayList<String>()
        val take = FakeHomeTake(committer = CountingCommitter(), log = log)
        val settle = TakeSettle(InMemoryHistoryStore(log), FakeTakenClipboard())
        val ctrl = TakeYieldController(
            background = FakeBackground(),
            main = FakeMainPost(),
            take = take,
            settle = settle,
            takenNotice = { lines.add(it) },
            pushState = { },
            onSettled = { },
            generation = { 0 },
        ).apply {
            releaseWatch = { watcher }
            onWatcherCleared = { cleared.add(true) }
        }
        ctrl.onTakeMicTaken()
        assertEquals("wiring: the cut brings up the busy face", true, ctrl.micBusy.get())
        // The watcher fires after the busy face is up: the flag clears, the notice line is
        // cleared and the repaint hook runs exactly once.
        val freeOnce: () -> Unit = watcher.free!!
        freeOnce()
        assertEquals("wiring: the late free answer clears the flag", false, ctrl.micBusy.get())
        assertEquals("wiring: the late free answer calls the repaint hook once", listOf(true), cleared)
        assertEquals("wiring: the late free answer clears the notice line",
            listOf<String?>(TakenSentences.LEAD, TakenSentences.SAVED_AND_COPIED, null), lines)
        // A second free callback after the clear does nothing: no repaint, no new line.
        freeOnce()
        assertEquals("wiring: a second free answer does not repaint again", 1, cleared.size)
        assertEquals("wiring: a second free answer adds no second clear line", 3, lines.size)
        assertEquals("wiring: the clear stops the watcher once", 1, watcher.stops)
    }

    /** The no-op watcher a controller is given when no tile host wires a real one never busy's. */
    @Test
    fun `the default no op watcher runs with no side effect`() {
        // No releaseWatch set: the controller keeps its no-op default.
        val log = ArrayList<String>()
        val take = FakeHomeTake(committer = CountingCommitter(), log = log)
        val settle = TakeSettle(InMemoryHistoryStore(log), FakeTakenClipboard())
        val ctrl = TakeYieldController(
            background = FakeBackground(),
            main = FakeMainPost(),
            take = take,
            settle = settle,
            takenNotice = { },
            pushState = { },
            onSettled = { },
            generation = { 0 },
        )
        ctrl.beginRefusedAsTaken()
        assertTrue("wiring: the default controller busy's the face", ctrl.micBusy.get())
        ctrl.onBeginAccepted()
        assertEquals("wiring: the default controller clears the face", false, ctrl.micBusy.get())
    }
}
