package dev.breaker.dictation.updater

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the strict base64 rules and the lowercase hex helpers. */
internal class CanonicalEncodingTest {
    @Test
    fun paddedBase64Decodes() {
        assertArrayEquals(byteArrayOf(1, 2), decodeCanonicalBase64("AQI=")!!)
    }

    @Test
    fun unpaddedBase64IsRefused() {
        assertNull(decodeCanonicalBase64("AQI"))
    }

    @Test
    fun nonAlphabetBase64IsRefused() {
        assertNull(decodeCanonicalBase64("AQ!="))
    }

    @Test
    fun base64WithLineBreakIsRefused() {
        assertNull(decodeCanonicalBase64("AQ\nI="))
    }

    @Test
    fun emptyBase64IsRefused() {
        assertNull(decodeCanonicalBase64(""))
    }

    @Test
    fun digestLengthIsChecked() {
        assertFalse(isLowerSha256Hex("a".repeat(63)))
        assertFalse(isLowerSha256Hex("a".repeat(65)))
    }

    @Test
    fun lowerHexOnlyIsAccepted() {
        assertTrue(isLowerSha256Hex("a".repeat(64)))
        assertFalse(isLowerSha256Hex("A".repeat(64)))
    }

    @Test
    fun hexEncodesEveryByte() {
        assertEquals("0aff", toLowerHex(byteArrayOf(0x0A.toByte(), 0xFF.toByte())))
    }
}
