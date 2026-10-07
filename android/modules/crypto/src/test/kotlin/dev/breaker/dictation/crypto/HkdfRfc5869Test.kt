package dev.breaker.dictation.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * HKDF-SHA256 against the SHA-256 test cases of RFC 5869 appendix A.1, A.2 and
 * A.3, then a few properties that follow from the construction: the output is
 * prefix-consistent in its length, the salt and the info both reach the result,
 * an empty salt means 32 zero bytes, and the caller's arrays are left alone.
 */
class HkdfRfc5869Test {

    private val a1Ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    private val a1Salt = hex("000102030405060708090a0b0c")
    private val a1Info = hex("f0f1f2f3f4f5f6f7f8f9")
    private val a1Okm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

    private fun ByteArray.withLastByteFlipped(): ByteArray =
        copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }

    /** A failure means the basic extract-then-expand result differs from RFC 5869 appendix A.1. */
    @Test
    fun `RFC 5869 appendix A-1 basic case gives the published output`() {
        assertEquals("output for appendix A-1", a1Okm, hkdfSha256(a1Ikm, a1Salt, a1Info, 42).toHex())
    }

    /** A failure means inputs of 80 bytes or an output of 82 bytes (3 blocks) are mishandled. */
    @Test
    fun `RFC 5869 appendix A-2 longer inputs and output give the published output`() {
        val ikm = hex(
            """
            000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
            202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f
            404142434445464748494a4b4c4d4e4f
            """,
        )
        val salt = hex(
            """
            606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f
            808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f
            a0a1a2a3a4a5a6a7a8a9aaabacadaeaf
            """,
        )
        val info = hex(
            """
            b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf
            d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef
            f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff
            """,
        )
        val okm = "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
            "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
            "cc30c58179ec3e87c14c01d5c1f3434f1d87"

        assertEquals("output for appendix A-2", okm, hkdfSha256(ikm, salt, info, 82).toHex())
    }

    /** A failure means an empty salt or an empty info is mishandled, compared with RFC 5869 appendix A.3. */
    @Test
    fun `RFC 5869 appendix A-3 empty salt and empty info give the published output`() {
        // Appendix A.3 uses the same 22 byte ikm as appendix A.1.
        val okm = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"

        assertEquals("output for appendix A-3", okm, hkdfSha256(a1Ikm, ByteArray(0), ByteArray(0), 42).toHex())
    }

    /** A failure means the requested length is not honoured or the output is not a prefix of the longer one. */
    @Test
    fun `a shorter length gives the leading bytes of the appendix A-1 output`() {
        assertEquals("32 byte output", a1Okm.substring(0, 64), hkdfSha256(a1Ikm, a1Salt, a1Info, 32).toHex())
        assertEquals("1 byte output", a1Okm.substring(0, 2), hkdfSha256(a1Ikm, a1Salt, a1Info, 1).toHex())
    }

    /**
     * A failure means one info byte does not reach the result. The second assertion
     * catches an info that does not reach the result at all: the output for the
     * changed info would then equal the output for the unchanged info.
     */
    @Test
    fun `one changed info byte changes the output`() {
        val changed = hkdfSha256(a1Ikm, a1Salt, a1Info.withLastByteFlipped(), 42).toHex()
        val unchanged = hkdfSha256(a1Ikm, a1Salt, a1Info, 42).toHex()

        assertNotEquals("the output still equals the A-1 output after one info byte changed", a1Okm, changed)
        assertNotEquals("the output for the changed info equals the output for the unchanged info", unchanged, changed)
    }

    /**
     * A failure means one salt byte does not reach the result. The second assertion
     * catches a salt that does not reach the result at all: the output for the
     * changed salt would then equal the output for the unchanged salt.
     */
    @Test
    fun `one changed salt byte changes the output`() {
        val changed = hkdfSha256(a1Ikm, a1Salt.withLastByteFlipped(), a1Info, 42).toHex()
        val unchanged = hkdfSha256(a1Ikm, a1Salt, a1Info, 42).toHex()

        assertNotEquals("the output still equals the A-1 output after one salt byte changed", a1Okm, changed)
        assertNotEquals("the output for the changed salt equals the output for the unchanged salt", unchanged, changed)
    }

    /** A failure means the function modified an array that the caller owns. */
    @Test
    fun `the three input arrays are unchanged after a call`() {
        val ikm = a1Ikm.copyOf()
        val salt = a1Salt.copyOf()
        val info = a1Info.copyOf()

        hkdfSha256(ikm, salt, info, 42)

        assertEquals("ikm after the call", a1Ikm.toHex(), ikm.toHex())
        assertEquals("salt after the call", a1Salt.toHex(), salt.toHex())
        assertEquals("info after the call", a1Info.toHex(), info.toHex())
    }

    /** A failure means an empty salt is not treated as 32 zero bytes as RFC 5869 requires. */
    @Test
    fun `an empty salt equals a salt of 32 zero bytes`() {
        val empty = hkdfSha256(a1Ikm, ByteArray(0), ByteArray(0), 42).toHex()
        val zeros = hkdfSha256(a1Ikm, ByteArray(32), ByteArray(0), 42).toHex()

        assertEquals("empty salt versus 32 zero bytes", zeros, empty)
    }
}
