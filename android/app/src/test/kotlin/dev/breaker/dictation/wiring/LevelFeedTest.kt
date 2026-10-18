package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.overlay.TileState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tile that keeps every level it is given and the thread each one was given on. */
internal class LevelRecordingTile : TilePort {
    val levels: MutableList<Float> = ArrayList()
    val threads: MutableList<Thread> = ArrayList()

    override fun show(): TileShow = TileShow.SHOWN

    override fun hide() {}

    override fun setState(state: TileState) {}

    override fun showNotice(text: String) {}

    override fun clearNotice() {}

    override fun level(level: Float) {
        levels.add(level)
        threads.add(Thread.currentThread())
    }
}

class LevelFeedTest {

    @Test
    fun `publish queues the tile call and makes no call until the main queue runs`() {
        val tile = LevelRecordingTile()
        val main = ManualMain()
        val feed = LevelFeed(tile, main) { true }
        feed.publish(0.5f)
        assertTrue("app: publish must not call the tile before the main queue runs", tile.levels.isEmpty())
        assertEquals("app: publish must queue exactly one block on main", 1, main.pending)
        main.drain()
        assertEquals("app: the queued block must hand the level to the tile", listOf(0.5f), tile.levels)
    }

    @Test
    fun `a queued level reaches the tile only while the take is recording when the block runs`() {
        val tile = LevelRecordingTile()
        val main = ManualMain()
        var recording = true
        val feed = LevelFeed(tile, main) { recording }
        feed.publish(0.4f)
        recording = false
        main.drain()
        assertTrue("app: a level queued while recording must be dropped when the take has stopped", tile.levels.isEmpty())
        feed.publish(0.6f)
        recording = true
        main.drain()
        assertEquals("app: the state must be read when the block runs, so a level queued earlier can pass", listOf(0.6f), tile.levels)
    }

    @Test
    fun `a level queued during a take in transcribing is not shown`() {
        val tile = LevelRecordingTile()
        val main = ManualMain()
        var state = DictationState.RECORDING
        val feed = LevelFeed(tile, main) { state == DictationState.RECORDING }
        feed.publish(0.4f)
        state = DictationState.TRANSCRIBING
        main.drain()
        assertTrue("app: transcribing is not recording, so the level must not reach the tile", tile.levels.isEmpty())
    }

    @Test
    fun `the tile call runs on the thread that drains the main queue and not on the publishing thread`() {
        val testThread = Thread.currentThread()
        val tile = LevelRecordingTile()
        val main = ManualMain()
        val feed = LevelFeed(tile, main) { true }
        val workerThread = CompletableDeferred<Thread>()
        val scope = CoroutineScope(Dispatchers.Default)
        val worker = scope.launch {
            workerThread.complete(Thread.currentThread())
            feed.publish(0.7f)
        }
        val publisher = awaitBounded("the publishing worker to start", workerThread)
        joinBounded("the publishing worker", worker)
        scope.cancel()
        assertTrue("app: publish from the worker must not call the tile on the worker", tile.levels.isEmpty())
        main.drain()
        assertEquals("app: the level must reach the tile once the main queue runs", listOf(0.7f), tile.levels)
        assertSame("app: the tile must be called on the thread that drains the main queue", testThread, tile.threads.single())
        assertNotSame("app: the tile must not be called on the publishing worker", publisher, tile.threads.single())
    }
}

/** Waits for [job] to finish for a bounded time and fails by name when it does not. */
internal fun joinBounded(what: String, job: Job) {
    try {
        runBlocking { withTimeout(5_000L) { job.join() } }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("app: timed out waiting for $what to finish")
    }
}
