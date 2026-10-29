package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Pins the streamed digest: the result kinds, the byte cap and the reads. */
internal class StreamDigestTest {
    private fun payload(): ByteArray = ManifestVectors.PAYLOAD_TEXT.toByteArray(Charsets.UTF_8)

    private fun countingEmptyStream(reads: IntArray): InputStream = object : InputStream() {
        override fun read(): Int {
            reads[0]++
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads[0]++
            return -1
        }
    }

    @Test
    fun matchingStreamIsMatched() {
        val bytes = payload()
        val result = verifyStreamDigest(ByteArrayInputStream(bytes), ManifestVectors.DIGEST, 1024L)
        assertEquals(StreamDigestResult.Matched(bytes.size.toLong()), result)
    }

    @Test
    fun changedByteIsMismatched() {
        val bytes = payload()
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val result = verifyStreamDigest(ByteArrayInputStream(bytes), ManifestVectors.DIGEST, 1024L)
        assertEquals(StreamDigestResult.Mismatched(bytes.size.toLong()), result)
    }

    @Test
    fun oneByteOverCapIsTooLarge() {
        val size = payload().size.toLong()
        val result = verifyStreamDigest(ByteArrayInputStream(payload()), ManifestVectors.DIGEST, size - 1)
        assertEquals(StreamDigestResult.TooLarge, result)
    }

    @Test
    fun exactCapStreamIsNotTooLarge() {
        val size = payload().size.toLong()
        val result = verifyStreamDigest(ByteArrayInputStream(payload()), ManifestVectors.DIGEST, size)
        assertEquals(StreamDigestResult.Matched(size), result)
    }

    @Test
    fun oneByteShortIsMismatched() {
        val short = payload().copyOf(payload().size - 1)
        val result = verifyStreamDigest(ByteArrayInputStream(short), ManifestVectors.DIGEST, 1024L)
        assertEquals(StreamDigestResult.Mismatched(short.size.toLong()), result)
    }

    @Test
    fun emptyStreamIsMismatched() {
        val result = verifyStreamDigest(ByteArrayInputStream(ByteArray(0)), ManifestVectors.DIGEST, 1024L)
        assertEquals(StreamDigestResult.Mismatched(0L), result)
    }

    @Test
    fun ioErrorIsUnreadable() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("test failure")

            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("test failure")
        }
        assertEquals(StreamDigestResult.Unreadable, verifyStreamDigest(failing, ManifestVectors.DIGEST, 1024L))
    }

    @Test
    fun zeroByteReadsAreUnreadable() {
        var calls = 0
        val zeros = object : InputStream() {
            override fun read(): Int = -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                calls++
                return if (calls <= 3) 0 else -1
            }
        }
        assertEquals(StreamDigestResult.Unreadable, verifyStreamDigest(zeros, ManifestVectors.DIGEST, 1024L))
    }

    @Test
    fun negativeCapIsRefused() {
        try {
            verifyStreamDigest(ByteArrayInputStream(payload()), ManifestVectors.DIGEST, -1L)
            fail("a negative cap must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("cap"))
        }
    }

    @Test
    fun upperCaseExpectedDigestIsRefusedBeforeRead() {
        val reads = IntArray(1)
        val result = verifyStreamDigest(countingEmptyStream(reads), ManifestVectors.DIGEST.uppercase(), 1024L)
        assertEquals(StreamDigestResult.ExpectedMalformed, result)
        assertEquals(0, reads[0])
    }

    @Test
    fun wrongLengthExpectedDigestIsRefusedBeforeRead() {
        val reads = IntArray(1)
        val result = verifyStreamDigest(countingEmptyStream(reads), ManifestVectors.DIGEST.dropLast(1), 1024L)
        assertEquals(StreamDigestResult.ExpectedMalformed, result)
        assertEquals(0, reads[0])
    }

    @Test
    fun readsStopAtCapPlusOne() {
        val consumed = LongArray(1)
        val source = ByteArrayInputStream(ByteArray(1000))
        val metered = object : InputStream() {
            override fun read(): Int {
                val b = source.read()
                if (b >= 0) consumed[0] += 1L
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = source.read(b, off, len)
                if (n > 0) consumed[0] += n.toLong()
                return n
            }
        }
        assertEquals(StreamDigestResult.TooLarge, verifyStreamDigest(metered, ManifestVectors.DIGEST, 10L))
        assertTrue(consumed[0] <= 11L)
    }

    @Test
    fun streamIsNotClosedByFunction() {
        val closed = BooleanArray(1)
        val inner = ByteArrayInputStream(payload())
        val watched = object : InputStream() {
            override fun read(): Int = inner.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)

            override fun close() {
                closed[0] = true
            }
        }
        verifyStreamDigest(watched, ManifestVectors.DIGEST, 1024L)
        assertFalse(closed[0])
    }
}
