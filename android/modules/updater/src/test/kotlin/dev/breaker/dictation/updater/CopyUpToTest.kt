package dev.breaker.dictation.updater

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the bounded copy: the limit, the short and empty inputs, the failures, and the rule that the input is never closed. */
internal class CopyUpToTest {

    /** A stream whose every read throws, as a broken file does. */
    private class FailingReadStream : InputStream() {
        override fun read(): Int = throw IOException("the test stream cannot be read")

        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("the test stream cannot be read")
    }

    /** A stream that answers zero bytes to its first read and then ends. */
    private class ZeroFirstReadStream : InputStream() {
        private var calls = 0

        override fun read(): Int = -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            calls += 1
            return if (calls == 1) 0 else -1
        }
    }

    @Test
    fun `copies exactly the limit when the input is longer`() {
        val out = ByteArrayOutputStream()
        val count = copyUpTo(ByteArrayInputStream(ByteArray(10) { it.toByte() }), 5L) { b, o, l -> out.write(b, o, l) }
        assertEquals(5L, count)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), out.toByteArray())
    }

    @Test
    fun `copies everything when the input is shorter than the limit`() {
        val out = ByteArrayOutputStream()
        val count = copyUpTo(ByteArrayInputStream(ByteArray(3) { it.toByte() }), 5L) { b, o, l -> out.write(b, o, l) }
        assertEquals(3L, count)
        assertArrayEquals(byteArrayOf(0, 1, 2), out.toByteArray())
    }

    @Test
    fun `empty input gives a count of zero`() {
        val count = copyUpTo(ByteArrayInputStream(ByteArray(0)), 5L) { _, _, _ -> }
        assertEquals(0L, count)
    }

    @Test
    fun `a zero limit copies nothing and reads nothing`() {
        val stream = TrackedStream(ByteArray(3))
        val count = copyUpTo(stream, 0L) { _, _, _ -> }
        assertEquals(0L, count)
        assertEquals(0, stream.readCalls)
    }

    @Test
    fun `a sink IOException gives null`() {
        val count = copyUpTo(ByteArrayInputStream(ByteArray(4)), 4L) { _, _, _ -> throw IOException("the sink cannot take bytes") }
        assertNull(count)
    }

    @Test
    fun `a read IOException gives null`() {
        assertNull(copyUpTo(FailingReadStream(), 4L) { _, _, _ -> })
    }

    @Test
    fun `a read that returns zero once gives null`() {
        assertNull(copyUpTo(ZeroFirstReadStream(), 4L) { _, _, _ -> })
    }

    @Test
    fun `the input is never closed`() {
        val stream = TrackedStream(ByteArray(3))
        copyUpTo(stream, 3L) { _, _, _ -> }
        assertFalse("the copy must not close its input", stream.closed)
    }
}
