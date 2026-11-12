package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A source that keeps the listener it was started with and delivers frames when the test says so. */
private class TapSource : AudioSource {
    private var listener: AudioListener? = null

    override fun start(listener: AudioListener) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
    }

    fun deliver(frame: FloatArray) {
        listener?.onFrame(frame)
    }
}

class PhraseFeedTapTest {

    @Test
    fun `every frame reaches the listener as the same array and the stream is offered it once`() {
        val source = TapSource()
        val stream = PhraseFakeWordStream()
        val received = ArrayList<FloatArray>()
        PhraseFeedTap(source) { stream }.start(AudioListener { received.add(it) })
        val frame = FloatArray(320)
        source.deliver(frame)
        assertEquals("the listener did not get exactly one frame", 1, received.size)
        assertSame("the listener got a copy of the frame", frame, received[0])
        assertEquals("the stream was offered a frame more than once", 1, stream.feeds)
    }

    @Test
    fun `the listener sees each frame before the stream is offered it`() {
        val source = TapSource()
        val stream = PhraseFakeWordStream()
        var feedsSeenByListener = -1
        PhraseFeedTap(source) { stream }.start(AudioListener { feedsSeenByListener = stream.feeds })
        source.deliver(FloatArray(320))
        assertEquals("the stream was offered a frame before the listener got it", 0, feedsSeenByListener)
    }

    @Test
    fun `a feed that throws still returns the frame to the capture`() {
        val source = TapSource()
        val stream = PhraseFakeWordStream()
        stream.feedError = IllegalStateException("the stream refused the frame")
        var delivered = 0
        PhraseFeedTap(source) { stream }.start(AudioListener { delivered += 1 })
        var thrown: Throwable? = null
        try {
            source.deliver(FloatArray(320))
            source.deliver(FloatArray(320))
            source.deliver(FloatArray(320))
        } catch (t: Throwable) {
            thrown = t
        }
        assertNull("a feed that threw reached the capture", thrown)
        assertEquals("a feed that threw kept frames from the listener", 3, delivered)
    }

    @Test
    fun `after the first failure the stream is not offered audio again in that start`() {
        val source = TapSource()
        val stream = PhraseFakeWordStream()
        stream.feedError = IllegalStateException("the stream refused the frame")
        PhraseFeedTap(source) { stream }.start(AudioListener { })
        source.deliver(FloatArray(320))
        source.deliver(FloatArray(320))
        source.deliver(FloatArray(320))
        assertEquals("the stream was offered audio after its first failure", 1, stream.feeds)
    }

    @Test
    fun `a linkage error from the stream does not reach the capture`() {
        val source = TapSource()
        val stream = PhraseFakeWordStream()
        stream.feedError = NoClassDefFoundError("the engine library is missing a class")
        var delivered = 0
        PhraseFeedTap(source) { stream }.start(AudioListener { delivered += 1 })
        var thrown: Throwable? = null
        try {
            source.deliver(FloatArray(320))
        } catch (t: Throwable) {
            thrown = t
        }
        assertNull("a linkage error from the stream reached the capture", thrown)
        assertEquals("the frame did not reach the listener", 1, delivered)
    }

    @Test
    fun `an out of memory error from the stream reaches the capture`() {
        val source = TapSource()
        val stream = PhraseFakeWordStream()
        stream.feedError = OutOfMemoryError("the process is out of memory")
        PhraseFeedTap(source) { stream }.start(AudioListener { })
        var caught: Throwable? = null
        try {
            source.deliver(FloatArray(320))
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue("an out of memory error from the stream was swallowed", caught is OutOfMemoryError)
    }

    @Test
    fun `the stream is offered each frame on the capture thread`() {
        val source = TapSource()
        val stream = PhraseFakeWordStream()
        PhraseFeedTap(source) { stream }.start(AudioListener { })
        source.deliver(FloatArray(320))
        assertSame("the stream was offered the frame on another thread", Thread.currentThread(), stream.feedThread)
    }
}
