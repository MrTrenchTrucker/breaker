package dev.breaker.dictation.updater

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The checked copy: the digest, the size cap and the failure outcomes, with hand-written streams and no file. */
internal class SessionCopyTest {

    @Test
    fun `matching bytes give Matched and the same bytes reach the output`() {
        val payload = "breaker update".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val result = copyChecked(ByteArrayInputStream(payload), out, sha256Hex(payload), CAP_BYTES)
        assertEquals(CheckedCopy.Matched, result)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `a digest that does not match gives Mismatched`() {
        val payload = "breaker update".toByteArray(Charsets.UTF_8)
        val other = "another file".toByteArray(Charsets.UTF_8)
        val result = copyChecked(ByteArrayInputStream(payload), ByteArrayOutputStream(), sha256Hex(other), CAP_BYTES)
        assertEquals(CheckedCopy.Mismatched, result)
    }

    @Test
    fun `a copy of exactly the cap gives Matched`() {
        val payload = ByteArray(100) { it.toByte() }
        val result = copyChecked(ByteArrayInputStream(payload), ByteArrayOutputStream(), sha256Hex(payload), 100L)
        assertEquals(CheckedCopy.Matched, result)
    }

    @Test
    fun `a copy one byte past the cap gives TooLarge`() {
        val payload = ByteArray(101) { it.toByte() }
        val result = copyChecked(ByteArrayInputStream(payload), ByteArrayOutputStream(), sha256Hex(payload), 100L)
        assertEquals(CheckedCopy.TooLarge, result)
    }

    @Test
    fun `a read error gives Failed`() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("the test stream cannot be read")
        }
        val result = copyChecked(broken, ByteArrayOutputStream(), sha256Hex(ByteArray(0)), CAP_BYTES)
        assertEquals(CheckedCopy.Failed, result)
    }

    @Test
    fun `an expected digest of the wrong shape gives Mismatched with no bytes written`() {
        val payload = "breaker update".toByteArray(Charsets.UTF_8)
        val input = CountingInput(payload)
        val out = ByteArrayOutputStream()
        val upper = sha256Hex(payload).uppercase()
        val result = copyChecked(input, out, upper, CAP_BYTES)
        assertEquals(CheckedCopy.Mismatched, result)
        assertEquals(0, out.size())
        assertEquals(0, input.reads)
    }

    @Test
    fun `the digest covers every chunk of an input longer than one read`() {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val result = copyChecked(ByteArrayInputStream(payload), ByteArrayOutputStream(), sha256Hex(payload), 100_000L)
        assertEquals(CheckedCopy.Matched, result)
    }

    @Test
    fun `copying leaves the source bytes and length unchanged`() {
        val source = "breaker update".toByteArray(Charsets.UTF_8)
        val before = source.copyOf()
        copyChecked(ByteArrayInputStream(source), ByteArrayOutputStream(), sha256Hex(source), CAP_BYTES)
        assertArrayEquals(before, source)
        assertEquals(before.size, source.size)
    }

    /** A stream that counts its read calls, so a test can see whether any byte was asked for. */
    private class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var reads: Int = 0

        override fun read(): Int {
            reads += 1
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reads += 1
            return super.read(b, off, len)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = toLowerHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/** The cap that the copy tests use: far above every payload they copy. */
private const val CAP_BYTES: Long = 1_048_576L
