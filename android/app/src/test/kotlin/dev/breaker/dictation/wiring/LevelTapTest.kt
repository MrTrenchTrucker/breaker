package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** An audio source that keeps the listener it was started with, so a test can drive frames through it. */
internal class LevelTapSource : AudioSource {
    var startCalls: Int = 0
        private set
    var stopCalls: Int = 0
        private set
    private var listener: AudioListener? = null

    override fun start(listener: AudioListener) {
        startCalls += 1
        this.listener = listener
    }

    override fun stop() {
        stopCalls += 1
    }

    /** Hands [frame] to the listener the source was started with. */
    fun deliver(frame: FloatArray) {
        checkNotNull(listener) { "app: the test source was not started" }.onFrame(frame)
    }
}

class LevelTapTest {

    private fun tapWith(
        source: AudioSource,
        tile: LevelRecordingTile = LevelRecordingTile(),
        main: MainPost = ManualMain(),
    ): LevelTap = LevelTap(source, LevelFeed(tile, main) { true })

    /** The lead-in: eight frames of 1067 samples at -50 dBFS, which seed the noise floor at -50 before a test's own input. */
    private fun lead(): List<FloatArray> = List(8) { FloatArray(1067) { 0.0031622777f } }

    @Test
    fun `every frame reaches the listener as the same array and in the order it came`() {
        val source = LevelTapSource()
        val tap = tapWith(source)
        val sent = listOf(FloatArray(320) { 0.1f }, FloatArray(512) { 0.2f }, FloatArray(100) { 0.3f }, FloatArray(0))
        val snapshots = sent.map { it.copyOf() }
        val received = ArrayList<FloatArray>()
        tap.start(AudioListener { frame -> received.add(frame) })
        sent.forEach { source.deliver(it) }
        assertEquals("app: every frame must reach the listener, the empty frame too", sent.size, received.size)
        sent.zip(received).forEach { (expected, actual) -> assertSame("app: a frame must reach the listener as the same array", expected, actual) }
        snapshots.zip(received).forEach { (expected, actual) ->
            assertArrayEquals("app: a frame must reach the listener with the samples it was given", expected, actual, 0f)
        }
    }

    @Test
    fun `the listener sees each frame before the level is posted`() {
        val source = LevelTapSource()
        val events = ArrayList<String>()
        val main = object : MainPost {
            override fun post(block: () -> Unit) {
                events.add("post")
            }
        }
        val tap = tapWith(source, main = main)
        tap.start(AudioListener { events.add("frame") })
        source.deliver(FloatArray(1067) { 1f })
        assertEquals("app: the frame must go to the listener before any level is posted", listOf("frame", "post"), events)
    }

    @Test
    fun `a short tail is forwarded as the same array and its samples count toward the window`() {
        val source = LevelTapSource()
        val tile = LevelRecordingTile()
        val main = ManualMain()
        val tap = tapWith(source, tile, main)
        val full = FloatArray(1000) { 1f }
        val tail = FloatArray(100) { 1f }
        val received = ArrayList<FloatArray>()
        tap.start(AudioListener { frame -> received.add(frame) })
        lead().forEach { source.deliver(it) }
        main.drain()
        tile.levels.clear()
        source.deliver(full)
        assertTrue("app: 1000 samples must not post a level yet", main.pending == 0)
        source.deliver(tail)
        assertSame("app: the short tail must reach the listener as the same array", tail, received.last())
        main.drain()
        assertEquals("app: the tail's samples must count, so the window posts one level", listOf(1f), tile.levels)
    }

    @Test
    fun `an empty frame is forwarded unchanged and posts no level`() {
        val source = LevelTapSource()
        val main = ManualMain()
        val tap = tapWith(source, main = main)
        val empty = FloatArray(0)
        val received = ArrayList<FloatArray>()
        tap.start(AudioListener { frame -> received.add(frame) })
        source.deliver(empty)
        assertSame("app: the empty frame must reach the listener as the same array", empty, received.single())
        assertEquals("app: an empty frame must post nothing", 0, main.pending)
    }

    @Test
    fun `the tap hands a level straight to a sink on the calling thread and needs no feed`() {
        val testThread = Thread.currentThread()
        val source = LevelTapSource()
        val levels = ArrayList<Float>()
        val threads = ArrayList<Thread>()
        val tap = LevelTap(source, LevelSink { level ->
            levels.add(level)
            threads.add(Thread.currentThread())
        })
        tap.start(AudioListener { })
        lead().forEach { source.deliver(it) }
        levels.clear()
        threads.clear()
        source.deliver(FloatArray(1067) { 1f })
        assertEquals("app: one loud window must reach the sink directly as 1", listOf(1f), levels)
        assertSame("app: the sink must be called on the calling thread", testThread, threads.single())
    }

    @Test
    fun `start and stop pass through to the inner source once each`() {
        val source = LevelTapSource()
        val tap = tapWith(source)
        tap.start(AudioListener { })
        assertEquals("app: start must reach the inner source once", 1, source.startCalls)
        tap.stop()
        assertEquals("app: stop must reach the inner source once", 1, source.stopCalls)
    }

    @Test
    fun `a level path that throws does not stop the frames from reaching the listener`() {
        val source = LevelTapSource()
        val throwing = object : MainPost {
            override fun post(block: () -> Unit) {
                throw RuntimeException("the main queue refused the level")
            }
        }
        val tap = tapWith(source, main = throwing)
        val received = ArrayList<FloatArray>()
        tap.start(AudioListener { frame -> received.add(frame) })
        source.deliver(FloatArray(1067) { 1f })
        source.deliver(FloatArray(320) { 0.1f })
        assertEquals("app: both frames must reach the listener although the level path threw", 2, received.size)
    }

    @Test
    fun `each start makes a fresh meter so a loud take does not smooth the next take down`() {
        val source = LevelTapSource()
        val tile = LevelRecordingTile()
        val main = ManualMain()
        val tap = tapWith(source, tile, main)
        tap.start(AudioListener { })
        lead().forEach { source.deliver(it) }
        source.deliver(FloatArray(1067) { 1f })
        main.drain()
        tile.levels.clear()
        tap.stop()
        tap.start(AudioListener { })
        lead().forEach { source.deliver(it) }
        source.deliver(FloatArray(1067) { 0f })
        main.drain()
        assertEquals("app: the second take must start from 0, not fall from the first take's 1", List(9) { 0f }, tile.levels)
    }

    @Test
    fun `frames driven from a worker thread are measured there and the tile is called on the test thread`() {
        val testThread = Thread.currentThread()
        val source = LevelTapSource()
        val tile = LevelRecordingTile()
        val main = ManualMain()
        val tap = tapWith(source, tile, main)
        tap.start(AudioListener { })
        val workerThread = CompletableDeferred<Thread>()
        val leadDone = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.Default)
        val worker = scope.launch {
            workerThread.complete(Thread.currentThread())
            lead().forEach { source.deliver(it) }
            leadDone.complete(Unit)
            drained.await()
            source.deliver(FloatArray(1067) { 1f })
        }
        val driver = awaitBounded("the frame worker to start", workerThread)
        awaitBounded("the lead-in on the frame worker", leadDone)
        main.drain()
        tile.levels.clear()
        tile.threads.clear()
        drained.complete(Unit)
        joinBounded("the frame worker", worker)
        scope.cancel()
        assertEquals("app: the worker's frame must post one level to the main queue", 1, main.pending)
        assertTrue("app: the level must not reach the tile before the main queue runs", tile.levels.isEmpty())
        main.drain()
        assertEquals("app: the level must reach the tile once the main queue runs", listOf(1f), tile.levels)
        assertSame("app: the tile must be called on the thread that drains the main queue", testThread, tile.threads.single())
        assertNotSame("app: the tile must not be called on the frame worker", driver, tile.threads.single())
    }
}
