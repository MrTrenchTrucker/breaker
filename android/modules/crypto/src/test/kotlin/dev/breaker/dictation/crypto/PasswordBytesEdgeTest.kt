package dev.breaker.dictation.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins passwordBytes edge cases that main's KdfImplTest does not pin:
 * the fi ligature is kept (no NFKC expansion), a trailing space is kept
 * (no trimming), upper case letters are kept (no lowercasing).
 *
 * passwordBytes takes a String, normalises to Unicode NFC, then encodes
 * as UTF-8. Expected bytes computed independently with Python
 * (unicodedata + UTF-8).
 *
 * Rows 4-6 document NFC itself (angstrom U+212B, A plus combining ring,
 * Hangul conjoining jamo). Main's V2 already covers NFC, so these rows
 * add no kill beyond what main's tests already pin.
 */
class PasswordBytesEdgeTest {

    /** Encodes bytes as lowercase hex, two digits per byte. */
    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        return joinToString("") { b ->
            val v = b.toInt() and 0xff
            "" + digits[v shr 4] + digits[v and 0x0f]
        }
    }

    @Test
    fun `the fi ligature is kept as one letter`() {
        assertEquals("fi ligature kept", "efac81", passwordBytes("\uFB01").toHex())
    }

    @Test
    fun `a trailing space is kept`() {
        assertEquals("trailing space kept", "61626320", passwordBytes("abc ").toHex())
    }

    @Test
    fun `upper case letters are kept`() {
        assertEquals("upper case kept", "414243", passwordBytes("ABC").toHex())
    }

    @Test
    fun `angstrom sign normalises to capital A with ring`() {
        assertEquals("angstrom sign", "c385", passwordBytes("\u212B").toHex())
    }

    @Test
    fun `A with combining ring normalises to capital A with ring`() {
        assertEquals("A plus combining ring", "c385", passwordBytes("A\u030A").toHex())
    }

    @Test
    fun `Hangul conjoining letters normalise to one syllable`() {
        assertEquals("Hangul conjoining jamo", "eab080", passwordBytes("\u1100\u1161").toHex())
    }
}
