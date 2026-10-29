package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the strict UTF-8 decode: valid text round-trips, and every malformed form gives null with no replacement character. */
internal class StrictUtf8Test {

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `valid text decodes to the same text`() {
        val text = "h\u00e9llo \u20ac \uD834\uDD1E"
        assertEquals(text, decodeStrictUtf8(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `an overlong form gives null`() {
        assertNull(decodeStrictUtf8(bytesOf(0xC0, 0x80)))
        assertNull(decodeStrictUtf8(bytesOf(0xE0, 0x80, 0x80)))
        assertNull(decodeStrictUtf8(bytesOf(0xF0, 0x80, 0x80, 0x80)))
    }

    @Test
    fun `a lone 0xFF byte gives null`() {
        assertNull(decodeStrictUtf8(bytesOf(0xFF)))
    }

    @Test
    fun `a truncated three byte sequence gives null`() {
        assertNull(decodeStrictUtf8(bytesOf(0xE2, 0x82)))
    }

    @Test
    fun `an encoded surrogate gives null`() {
        assertNull(decodeStrictUtf8(bytesOf(0xED, 0xA0, 0x80)))
    }

    @Test
    fun `a code point above U+10FFFF gives null`() {
        assertNull(decodeStrictUtf8(bytesOf(0xF4, 0x90, 0x80, 0x80)))
    }

    @Test
    fun `a byte order mark decodes to U+FEFF`() {
        assertEquals("\uFEFF", decodeStrictUtf8(bytesOf(0xEF, 0xBB, 0xBF)))
    }
}
