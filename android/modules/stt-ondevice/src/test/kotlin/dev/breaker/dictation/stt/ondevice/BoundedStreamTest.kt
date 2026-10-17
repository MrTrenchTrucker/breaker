package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class BoundedStreamTest {

    /** A source that records how many bytes it handed out (read or skipped) and whether it was closed. */
    private class CountingSource(size: Int) : FilterInputStream(ByteArrayInputStream(ByteArray(size) { (it % 251).toByte() })) {
        var delivered = 0L
        var closed = false

        override fun read(): Int {
            val v = super.read()
            if (v >= 0) delivered += 1
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) delivered += n
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) delivered += s
            return s
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun bounded(source: InputStream, max: Long) = BoundedStream(source, max)

    /** Read with a buffer of [bufferSize] until the end; returns how many bytes came out. */
    private fun drain(stream: InputStream, bufferSize: Int): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        var calls = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) return total
            total += n
            calls += 1
            if (calls > 10_000) fail("reads made no progress after $total bytes")
        }
    }

    private fun assertLimitHit(claim: String, action: () -> Unit) {
        try {
            action()
        } catch (e: IOException) {
            assertTrue("$claim: expected StreamLimitExceededException but got $e", e is StreamLimitExceededException)
            return
        }
        fail("$claim: expected StreamLimitExceededException but nothing was thrown")
    }

    @Test
    fun `a source of exactly the limit is read to its end without a throw`() {
        for (bufferSize in listOf(1, 3, 10, 64)) {
            val stream = bounded(CountingSource(10), 10L)
            assertEquals("bytes read with a buffer of $bufferSize", 10L, drain(stream, bufferSize))
            assertEquals("end of stream after the limit", -1, stream.read())
        }
    }

    @Test
    fun `a source one byte over the limit throws StreamLimitExceededException`() {
        for (bufferSize in listOf(1, 3, 10, 64)) {
            assertLimitHit("buffer of $bufferSize over a source of 11 with limit 10") {
                drain(bounded(CountingSource(11), 10L), bufferSize)
            }
        }
    }

    @Test
    fun `a source shorter than the limit ends normally`() {
        assertEquals("bytes read", 5L, drain(bounded(CountingSource(5), 10L), 4))
    }

    @Test
    fun `single byte reads are counted`() {
        val stream = bounded(CountingSource(4), 3L)
        for (i in 1..3) assertTrue("byte $i is within the limit", stream.read() >= 0)
        assertLimitHit("the fourth single byte read with limit 3") { stream.read() }
    }

    @Test
    fun `a bulk read counts the bytes it returns`() {
        val stream = bounded(CountingSource(100), 10L)
        val buffer = ByteArray(4)
        assertEquals(4, stream.read(buffer, 0, 4))
        assertEquals(4, stream.read(buffer, 0, 4))
        assertEquals("the read that reaches the limit exactly", 2, stream.read(buffer, 0, 2))
        assertLimitHit("the first byte past the limit") { stream.read(buffer, 0, 1) }
    }

    @Test
    fun `a bulk read never pulls more than one byte past the limit from the source`() {
        val source = CountingSource(1000)
        val stream = bounded(source, 10L)
        assertLimitHit("a 100 byte read with limit 10") { stream.read(ByteArray(100), 0, 100) }
        assertEquals("bytes the source handed out", 11L, source.delivered)
    }

    @Test
    fun `a bulk read inside the limit asks the source for no more than it was asked for`() {
        val source = CountingSource(1000)
        val stream = bounded(source, 10L)
        assertEquals(4, stream.read(ByteArray(100), 0, 4))
        assertEquals("bytes the source handed out", 4L, source.delivered)
    }

    @Test
    fun `skipped bytes are counted`() {
        val atLimit = bounded(CountingSource(100), 10L)
        assertEquals("skip of exactly the limit", 10L, atLimit.skip(10L))
        assertLimitHit("one more skipped byte") { atLimit.skip(1L) }

        val source = CountingSource(100)
        assertLimitHit("one skip past the limit") { bounded(source, 10L).skip(50L) }
        assertEquals("bytes the source skipped", 11L, source.delivered)
    }

    @Test
    fun `a skip of zero or less counts nothing`() {
        val stream = bounded(CountingSource(10), 10L)
        assertEquals(0L, stream.skip(0L))
        assertEquals(0L, stream.skip(-5L))
        assertEquals("the whole limit is still available", 10L, drain(stream, 4))
    }

    @Test
    fun `reads and skips share one count`() {
        val readThenSkip = bounded(CountingSource(100), 10L)
        assertEquals(6, readThenSkip.read(ByteArray(6), 0, 6))
        assertEquals(4L, readThenSkip.skip(4L))
        assertLimitHit("a byte after 6 read and 4 skipped") { readThenSkip.read() }

        val overshoot = bounded(CountingSource(100), 10L)
        assertEquals(6, overshoot.read(ByteArray(6), 0, 6))
        assertLimitHit("a skip of 5 after 6 read") { overshoot.skip(5L) }
    }

    @Test
    fun `after the limit is exceeded every later read and skip throws again`() {
        val source = CountingSource(100)
        val stream = bounded(source, 5L)
        assertLimitHit("the first throw") { stream.read(ByteArray(50), 0, 50) }
        val handedOut = source.delivered
        assertLimitHit("a later single read") { stream.read() }
        assertLimitHit("a later bulk read") { stream.read(ByteArray(4), 0, 4) }
        assertLimitHit("a later zero length read") { stream.read(ByteArray(4), 0, 0) }
        assertLimitHit("a later skip") { stream.skip(1L) }
        assertEquals("the source is not touched again", handedOut, source.delivered)
    }

    @Test
    fun `a limit of zero allows an empty source and refuses the first byte`() {
        assertEquals("empty source", -1, bounded(CountingSource(0), 0L).read())
        assertLimitHit("one byte with limit 0") { bounded(CountingSource(1), 0L).read() }
        assertLimitHit("a bulk read with limit 0") { bounded(CountingSource(8), 0L).read(ByteArray(4), 0, 4) }
    }

    @Test
    fun `a zero length read returns zero and counts nothing`() {
        val stream = bounded(CountingSource(10), 10L)
        assertEquals(0, stream.read(ByteArray(4), 0, 0))
        assertEquals("the whole limit is still available", 10L, drain(stream, 4))
    }

    @Test
    fun `the largest limit does not overflow`() {
        val stream = bounded(CountingSource(100), Long.MAX_VALUE)
        assertEquals(60, stream.read(ByteArray(60), 0, 60))
        assertEquals("skip of the largest long", 40L, stream.skip(Long.MAX_VALUE))
        assertEquals(-1, stream.read())
    }

    @Test
    fun `a negative limit is refused when the stream is made`() {
        try {
            bounded(CountingSource(1), -1L)
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("a negative limit must be refused with IllegalArgumentException")
    }

    @Test
    fun `mark and reset are not supported`() {
        val stream = bounded(CountingSource(10), 10L)
        assertFalse(stream.markSupported())
        try {
            stream.reset()
        } catch (e: IOException) {
            return
        }
        fail("reset must throw IOException")
    }

    @Test
    fun `closing the stream closes the source`() {
        val source = CountingSource(1)
        bounded(source, 1L).close()
        assertTrue("the source was closed", source.closed)
    }

    @Test
    fun `the exception is an IOException so a caller that catches IOException sees it`() {
        val thrown: Throwable = StreamLimitExceededException()
        assertTrue("the limit exception must be an IOException", thrown is IOException)
    }
}
