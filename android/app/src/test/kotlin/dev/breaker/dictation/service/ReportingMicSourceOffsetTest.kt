package dev.breaker.dictation.service

import dev.breaker.dictation.audio.MicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Protects the read pass-through of the capture-ended decorator: the buffer, the offset and the length
 * reach the real source exactly as given. The other tests use sources that ignore all three.
 */
internal class ReportingMicSourceOffsetTest {

    /** A source that keeps the arguments of its reads and answers a set count. */
    private class SpyMicSource : MicSource {
        override val sampleRateHz: Int = 16000
        override val channelCount: Int = 1
        val buffers: MutableList<ShortArray> = ArrayList()
        val offsets: MutableList<Int> = ArrayList()
        val lengths: MutableList<Int> = ArrayList()

        override fun open() = Unit

        override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
            buffers.add(buffer)
            offsets.add(offset)
            lengths.add(lengthInShorts)
            return 5
        }

        override fun close() = Unit
    }

    private val spy = SpyMicSource()
    private val source = ReportingMicSource(spy) { }

    @Test
    fun `read passes the buffer, the offset and the length through unchanged`() {
        val buffer = ShortArray(32)
        val count = source.read(buffer, 7, 13)
        assertEquals("app: the real source must be read once", 1, spy.offsets.size)
        assertSame("app: the same buffer must reach the real source", buffer, spy.buffers[0])
        assertEquals("app: the offset must reach the real source unchanged", 7, spy.offsets[0])
        assertEquals("app: the length must reach the real source unchanged", 13, spy.lengths[0])
        assertEquals("app: the count must come back unchanged", 5, count)
    }

    @Test
    fun `two reads with different offsets and lengths each reach the real source as given`() {
        val buffer = ShortArray(64)
        source.read(buffer, 0, 64)
        source.read(buffer, 20, 4)
        assertEquals("app: the offsets must reach the real source in order", listOf(0, 20), spy.offsets)
        assertEquals("app: the lengths must reach the real source in order", listOf(64, 4), spy.lengths)
    }
}
