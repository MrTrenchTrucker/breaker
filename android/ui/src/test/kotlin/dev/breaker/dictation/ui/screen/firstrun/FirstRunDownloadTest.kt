package dev.breaker.dictation.ui.screen.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download page's memory: its phases, its numbers and the rule that a download starts only once.
 * Every expected value is written out here. The re-entry rule has its own tests, with a counting
 * stand-in for the app's download start.
 */
class FirstRunDownloadTest {
    /** Stands in for the app's download start and counts how often it is asked. */
    private class StartCounter {
        var count: Int = 0
            private set

        fun start() {
            count++
        }
    }

    /** Enters the page the way the page does: asks, and starts the download only when allowed. */
    private fun enter(tracker: DownloadTracker, starts: StartCounter) {
        if (tracker.requestStart()) starts.start()
    }

    /** A tracker that has failed once, so that a new start is allowed. */
    private fun failed(): DownloadTracker = DownloadTracker().apply {
        onProgress(1, 10)
        onStopped(false, "stopped")
    }

    @Test
    fun `a new tracker is idle with zero and zero`() {
        val tracker = DownloadTracker()
        assertEquals("ui: first run: a new tracker is not idle", DownloadPhase.IDLE, tracker.phase)
        assertEquals("ui: first run: a new tracker has done megabytes", 0, tracker.doneMb)
        assertEquals("ui: first run: a new tracker has total megabytes", 0, tracker.totalMb)
    }

    @Test
    fun `requestStart is true from idle and from failed and moves to running with zero numbers`() {
        val fresh = DownloadTracker()
        assertTrue("ui: first run: a start from idle was refused", fresh.requestStart())
        assertEquals("ui: first run: a start from idle is not running", DownloadPhase.RUNNING, fresh.phase)
        assertEquals("ui: first run: a start from idle kept done megabytes", 0, fresh.doneMb)
        assertEquals("ui: first run: a start from idle kept total megabytes", 0, fresh.totalMb)
        val again = failed()
        assertTrue("ui: first run: a start from failed was refused", again.requestStart())
        assertEquals("ui: first run: a start from failed is not running", DownloadPhase.RUNNING, again.phase)
        assertEquals("ui: first run: a start from failed kept done megabytes", 0, again.doneMb)
        assertEquals("ui: first run: a start from failed kept total megabytes", 0, again.totalMb)
    }

    @Test
    fun `requestStart is false in running, checking and ready and changes nothing`() {
        val running = DownloadTracker().apply { onProgress(3, 9) }
        assertFalse("ui: first run: a start while running was allowed", running.requestStart())
        assertEquals("ui: first run: a refused start changed the running phase", DownloadPhase.RUNNING, running.phase)
        assertEquals("ui: first run: a refused start changed the running done megabytes", 3, running.doneMb)
        assertEquals("ui: first run: a refused start changed the running total megabytes", 9, running.totalMb)
        val checking = DownloadTracker().apply { onProgress(3, 9); onChecking() }
        assertFalse("ui: first run: a start while checking was allowed", checking.requestStart())
        assertEquals("ui: first run: a refused start changed the checking phase", DownloadPhase.CHECKING, checking.phase)
        assertEquals("ui: first run: a refused start changed the checking done megabytes", 3, checking.doneMb)
        val ready = DownloadTracker().apply { onModelReady() }
        assertFalse("ui: first run: a start while ready was allowed", ready.requestStart())
        assertEquals("ui: first run: a refused start changed the ready phase", DownloadPhase.READY, ready.phase)
    }

    @Test
    fun `onProgress sets running and the numbers`() {
        val tracker = DownloadTracker()
        tracker.onProgress(12, 366)
        assertEquals("ui: first run: progress did not set running", DownloadPhase.RUNNING, tracker.phase)
        assertEquals("ui: first run: progress did not set done megabytes", 12, tracker.doneMb)
        assertEquals("ui: first run: progress did not set total megabytes", 366, tracker.totalMb)
    }

    @Test
    fun `negative numbers become zero`() {
        val tracker = DownloadTracker()
        tracker.onProgress(-5, -7)
        assertEquals("ui: first run: negative done megabytes were kept", 0, tracker.doneMb)
        assertEquals("ui: first run: negative total megabytes were kept", 0, tracker.totalMb)
        assertEquals("ui: first run: negative progress changed the phase", DownloadPhase.RUNNING, tracker.phase)
    }

    @Test
    fun `onProgress is ignored in checking and ready`() {
        val checking = DownloadTracker().apply { onProgress(3, 9); onChecking() }
        checking.onProgress(50, 60)
        assertEquals("ui: first run: progress changed the checking phase", DownloadPhase.CHECKING, checking.phase)
        assertEquals("ui: first run: progress changed the checking numbers", 3, checking.doneMb)
        assertEquals("ui: first run: progress changed the checking total", 9, checking.totalMb)
        val ready = DownloadTracker().apply { onModelReady() }
        ready.onProgress(50, 60)
        assertEquals("ui: first run: progress changed the ready phase", DownloadPhase.READY, ready.phase)
    }

    @Test
    fun `onChecking from running gives checking and keeps the numbers`() {
        val tracker = DownloadTracker().apply { onProgress(366, 366) }
        tracker.onChecking()
        assertEquals("ui: first run: checking did not set the checking phase", DownloadPhase.CHECKING, tracker.phase)
        assertEquals("ui: first run: checking dropped done megabytes", 366, tracker.doneMb)
        assertEquals("ui: first run: checking dropped total megabytes", 366, tracker.totalMb)
    }

    @Test
    fun `onChecking is ignored when ready`() {
        val ready = DownloadTracker().apply { onModelReady() }
        ready.onChecking()
        assertEquals("ui: first run: checking changed the ready phase", DownloadPhase.READY, ready.phase)
    }

    @Test
    fun `onStopped gives ready when the model is ready and keeps the numbers`() {
        val tracker = DownloadTracker().apply { onProgress(3, 9) }
        tracker.onStopped(true, null)
        assertEquals("ui: first run: a ready model did not give the ready phase", DownloadPhase.READY, tracker.phase)
        assertEquals("ui: first run: the ready phase dropped done megabytes", 3, tracker.doneMb)
        assertEquals("ui: first run: the ready phase dropped total megabytes", 9, tracker.totalMb)
    }

    @Test
    fun `onStopped gives failed when not ready and a line is given, even an empty one, and zeroes the numbers`() {
        val tracker = DownloadTracker().apply { onProgress(3, 9) }
        tracker.onStopped(false, "")
        assertEquals("ui: first run: a stop with a line did not fail", DownloadPhase.FAILED, tracker.phase)
        assertEquals("ui: first run: a failed stop kept done megabytes", 0, tracker.doneMb)
        assertEquals("ui: first run: a failed stop kept total megabytes", 0, tracker.totalMb)
    }

    @Test
    fun `onStopped gives idle when not ready and the line is null, and zeroes the numbers`() {
        val tracker = DownloadTracker().apply { onProgress(3, 9) }
        tracker.onStopped(false, null)
        assertEquals("ui: first run: a stop without a line was not idle", DownloadPhase.IDLE, tracker.phase)
        assertEquals("ui: first run: an idle stop kept done megabytes", 0, tracker.doneMb)
        assertEquals("ui: first run: an idle stop kept total megabytes", 0, tracker.totalMb)
    }

    @Test
    fun `re-entry never starts a second download`() {
        val tracker = DownloadTracker()
        val starts = StartCounter()
        enter(tracker, starts)
        assertEquals("ui: first run: the first visit did not start the download", 1, starts.count)
        enter(tracker, starts)
        enter(tracker, starts)
        enter(tracker, starts)
        assertEquals("ui: first run: a second visit, a refresh or a second tap started a second download", 1, starts.count)
        tracker.onProgress(10, 100)
        enter(tracker, starts)
        assertEquals("ui: first run: a visit after progress started a second download", 1, starts.count)
    }

    @Test
    fun `a tracker that sees progress first refuses the start`() {
        val tracker = DownloadTracker()
        tracker.onProgress(5, 100)
        val starts = StartCounter()
        enter(tracker, starts)
        assertEquals("ui: first run: progress that came first let the page start a download", 0, starts.count)
        assertEquals("ui: first run: progress that came first changed the phase", DownloadPhase.RUNNING, tracker.phase)
    }

    @Test
    fun `after a stop with a failure line a new start is allowed`() {
        val tracker = DownloadTracker()
        val starts = StartCounter()
        enter(tracker, starts)
        tracker.onStopped(false, "stopped")
        enter(tracker, starts)
        assertEquals("ui: first run: a start after a failure was refused", 2, starts.count)
    }

    @Test
    fun `onModelReady sets ready`() {
        val tracker = DownloadTracker()
        tracker.onModelReady()
        assertEquals("ui: first run: onModelReady did not set the ready phase", DownloadPhase.READY, tracker.phase)
    }
}
