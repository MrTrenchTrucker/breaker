package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** An audio source that keeps the listener it was started with, so a test can drive frames through it. */
internal class PhraseFeedSource : AudioSource {
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

/** A feed that records each frame it is given, and throws [failWith] when that is set. */
internal class PhraseFeedRecorder {
    val calls: MutableList<FloatArray> = ArrayList()
    var failWith: Throwable? = null

    fun feed(frame: FloatArray) {
        calls.add(frame)
        failWith?.let { throw it }
    }
}

class PhraseFeedTapTest {

    @Test
    fun `the listener gets each frame before feed, as the same array`() {
        val source = PhraseFeedSource()
        val order: MutableList<String> = ArrayList()
        val heard: MutableList<FloatArray> = ArrayList()
        val fed: MutableList<FloatArray> = ArrayList()
        val tap = PhraseFeedTap(source) { frame ->
            order.add("feed")
            fed.add(frame)
        }
        tap.start(AudioListener { frame ->
            order.add("listener")
            heard.add(frame)
        })
        val frame = FloatArray(320) { 0.25f }
        source.deliver(frame)
        assertEquals("app: the listener must get the frame before feed", listOf("listener", "feed"), order)
        assertSame("app: the listener must get the frame array itself", frame, heard.single())
        assertSame("app: feed must get the same frame array", frame, fed.single())
    }

    @Test
    fun `feed runs once per frame and stop replays no frame`() {
        val source = PhraseFeedSource()
        val feeder = PhraseFeedRecorder()
        val heard: MutableList<FloatArray> = ArrayList()
        val tap = PhraseFeedTap(source, feeder::feed)
        tap.start(AudioListener { frame -> heard.add(frame) })
        val frames = List(3) { n -> FloatArray(160) { n.toFloat() } }
        frames.forEach { source.deliver(it) }
        tap.stop()
        assertEquals("app: feed must run once for each of the three frames", 3, feeder.calls.size)
        assertEquals("app: the listener must get each of the three frames", 3, heard.size)
        frames.forEachIndexed { i, frame ->
            assertSame("app: feed call $i must get frame $i itself", frame, feeder.calls[i])
        }
    }

    @Test
    fun `an exception from feed leaves the take recording and stoppable`() {
        val source = PhraseFeedSource()
        val feeder = PhraseFeedRecorder()
        feeder.failWith = IllegalStateException("app: test failure")
        val heard: MutableList<FloatArray> = ArrayList()
        val tap = PhraseFeedTap(source, feeder::feed)
        tap.start(AudioListener { frame -> heard.add(frame) })
        source.deliver(FloatArray(160) { 0.1f })
        source.deliver(FloatArray(160) { 0.2f })
        assertEquals("app: frames must keep reaching the listener after feed throws", 2, heard.size)
        tap.stop()
        assertEquals("app: the take must still stop on the inner source", 1, source.stopCalls)
    }

    @Test
    fun `a linkage error from feed leaves the take recording`() {
        val source = PhraseFeedSource()
        val feeder = PhraseFeedRecorder()
        feeder.failWith = NoClassDefFoundError("app: test failure")
        val heard: MutableList<FloatArray> = ArrayList()
        val tap = PhraseFeedTap(source, feeder::feed)
        tap.start(AudioListener { frame -> heard.add(frame) })
        source.deliver(FloatArray(160) { 0.1f })
        source.deliver(FloatArray(160) { 0.2f })
        assertEquals("app: frames must keep reaching the listener after a linkage error", 2, heard.size)
    }

    @Test
    fun `an out of memory error from feed is not swallowed`() {
        val source = PhraseFeedSource()
        val feeder = PhraseFeedRecorder()
        feeder.failWith = OutOfMemoryError("app: test failure")
        val tap = PhraseFeedTap(source, feeder::feed)
        tap.start(AudioListener { })
        try {
            source.deliver(FloatArray(160) { 0.1f })
            fail("app: an out of memory error from feed must propagate, not be caught")
        } catch (e: OutOfMemoryError) {
            assertEquals("app: the error that propagated must be the one feed threw", "app: test failure", e.message)
        }
    }

    @Test
    fun `after a failure feed is not called again in that take`() {
        val source = PhraseFeedSource()
        val feeder = PhraseFeedRecorder()
        feeder.failWith = IllegalStateException("app: test failure")
        val heard: MutableList<FloatArray> = ArrayList()
        val tap = PhraseFeedTap(source, feeder::feed)
        tap.start(AudioListener { frame -> heard.add(frame) })
        repeat(3) { source.deliver(FloatArray(160) { 0.1f }) }
        assertEquals("app: feed must be called once, then not again in the same take", 1, feeder.calls.size)
        assertEquals("app: the listener must still get all three frames", 3, heard.size)
    }

    @Test
    fun `a new start re-arms feeding`() {
        val source = PhraseFeedSource()
        val feeder = PhraseFeedRecorder()
        feeder.failWith = IllegalStateException("app: test failure")
        val tap = PhraseFeedTap(source, feeder::feed)
        tap.start(AudioListener { })
        source.deliver(FloatArray(160) { 0.1f })
        tap.stop()
        feeder.failWith = null
        tap.start(AudioListener { })
        val next = FloatArray(160) { 0.3f }
        source.deliver(next)
        assertEquals("app: a new start must feed again, so two calls in all", 2, feeder.calls.size)
        assertSame("app: the feed after the new start must get that frame", next, feeder.calls[1])
    }

    @Test
    fun `a frame after stop is harmless`() {
        val source = PhraseFeedSource()
        val feeder = PhraseFeedRecorder()
        val heard: MutableList<FloatArray> = ArrayList()
        val tap = PhraseFeedTap(source, feeder::feed)
        tap.start(AudioListener { frame -> heard.add(frame) })
        tap.stop()
        source.deliver(FloatArray(160) { 0.1f })
        assertEquals("app: a frame after stop must reach the listener without a failure", 1, heard.size)
        assertEquals("app: a frame after stop must reach feed once", 1, feeder.calls.size)
    }

    @Test
    fun `start and stop pass through to the inner source once each`() {
        val source = PhraseFeedSource()
        val tap = PhraseFeedTap(source) { }
        tap.start(AudioListener { })
        assertEquals("app: start must pass through to the inner source once", 1, source.startCalls)
        tap.stop()
        assertEquals("app: stop must pass through to the inner source once", 1, source.stopCalls)
    }
}
