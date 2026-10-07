package dev.breaker.dictation.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The password is converted to Unicode NFC and then to UTF-8 bytes, and nothing else
 * is done to it: no case folding, no trimming, no compatibility mapping (that would
 * be NFKC). The same password typed on two keyboards that spell an accented letter
 * differently must give the same bytes, so it derives the same keys.
 *
 * Expected bytes were computed with an independent Unicode library. Text is written
 * with escapes so the source file cannot silently change a spelling.
 */
class PasswordBytesTest {

    private fun assertBytes(message: String, expectedHex: String, password: String) {
        assertEquals(message, expectedHex, passwordBytes(password).toHex())
    }

    /**
     * A failure means e plus a combining acute accent was left as two code points (no normalisation,
     * or NFD): the bytes would be 65cc81.
     */
    @Test
    fun `e with combining acute becomes one composed letter`() {
        assertBytes("e plus combining acute accent", "c3a9", "e\u0301")
    }

    /**
     * A failure means the angstrom sign was not mapped to the Latin capital A with ring (no
     * normalisation): the bytes would be e284ab.
     */
    @Test
    fun `angstrom sign becomes capital A with ring`() {
        assertBytes("angstrom sign U+212B", "c385", "\u212B")
    }

    /**
     * A failure means A plus a combining ring was left decomposed (no normalisation, or NFD): the
     * bytes would be 41cc8a.
     */
    @Test
    fun `A with combining ring becomes capital A with ring`() {
        assertBytes("A plus combining ring above", "c385", "A\u030A")
    }

    /**
     * A failure means the fi ligature was expanded to the two letters f and i (NFKC): the bytes would
     * be 6669.
     */
    @Test
    fun `fi ligature is kept as it is`() {
        assertBytes("ligature fi U+FB01", "efac81", "\uFB01")
    }

    /**
     * A failure means Hangul conjoining letters were left separate (no normalisation, or NFD): the
     * bytes would be e18480e185a1.
     */
    @Test
    fun `Hangul conjoining letters become one syllable`() {
        assertBytes("Hangul U+1100 U+1161", "eab080", "\u1100\u1161")
    }

    /**
     * A failure means plain ASCII was changed in some way (for example case folding would give lower
     * case p).
     */
    @Test
    fun `plain ASCII password is unchanged`() {
        assertBytes("ASCII password", "50617373776f726431323321", "Password123!")
    }

    /** A failure means the trailing space was removed (trimming): the bytes would be 616263. */
    @Test
    fun `trailing space is kept`() {
        assertBytes("text ending in a space", "61626320", "abc ")
    }

    /** A failure means upper case letters were folded to lower case: the bytes would be 616263. */
    @Test
    fun `upper case letters are kept`() {
        assertBytes("upper case text", "414243", "ABC")
    }

    /** A failure means the two spellings of the same visible text give different bytes (no normalisation). */
    @Test
    fun `decomposed and composed spellings give the same bytes`() {
        val decomposed = passwordBytes("cafe\u0301")
        val composed = passwordBytes("caf\u00E9")
        assertEquals("composed bytes", "636166c3a9", composed.toHex())
        assertEquals("decomposed spelling gave other bytes", composed.toHex(), decomposed.toHex())
    }

    /** A failure means a whole passphrase with a decomposed letter does not reach the composed bytes. */
    @Test
    fun `whole passphrase with a decomposed letter gives the composed bytes`() {
        val bytes = passwordBytes("cafe\u0301 horse battery staple")
        assertEquals(
            "bytes of the passphrase",
            "636166c3a920686f727365206261747465727920737461706c65",
            bytes.toHex(),
        )
        assertNotEquals(
            "the raw decomposed bytes were used",
            "63616665cc8120686f727365206261747465727920737461706c65",
            bytes.toHex(),
        )
    }
}
