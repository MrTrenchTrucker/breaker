package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [SealedBoxOpener], which opens a libsodium `crypto_box_seal` box.
 *
 * The four boxes are REAL `crypto_box_seal` output made by libsodium (PyNaCl 1.6.2) to a fixed
 * recipient key pair, and each was opened by libsodium's own `crypto_box_seal_open` before it
 * was written here. The message in each is the bytes (i * 7 + 3) mod 256, so the expected
 * message is built here from that rule and not from the box.
 *
 * Every refusal is checked three ways: it is the one plain [CryptoRefused], it has no cause
 * that could carry a byte of the box or the key, and the body cipher was never called, which
 * is how these tests show that the tag is checked before any plaintext byte is made.
 */
class SealedBoxOpenerTest {
    /** The recipient private key the boxes were sealed to (32 bytes, a fixed pattern). */
    private val recipientPrivateKey = hex(
        "070a_0d10_1316_191c_1f22_2528_2b2e_3134",
        "373a_3d40_4346_494c_4f52_5558_5b5e_6164",
    )

    /** Another key pair's private key. It is not the recipient of any box here. */
    private val wrongRecipientPrivateKey = hex(
        "0510_1b26_313c_4752_5d68_737e_8994_9faa",
        "b5c0_cbd6_e1ec_f702_0d18_232e_3944_4f5a",
    )

    /** A real box of an empty message: exactly 48 bytes, the shortest legal box. */
    private val boxLen0 = hex(
        "5256_2dd3_f3a2_362f_0c86_bb70_3887_0697",
        "6912_5a98_5410_f257_4669_b129_c003_ff59",
        "f45d_a1d8_6fc5_981c_33b5_3047_65ce_d516",
    )

    /** A real box of a 1-byte message. */
    private val boxLen1 = hex(
        "d96a_f52e_da83_d6a4_e5a0_b62e_7433_db9f",
        "a2da_6959_9bdd_0187_6578_be9b_5bd5_2f15",
        "6f97_8243_6ee6_845e_563a_b09c_402a_23c5_e9",
    )

    /** A real box of a 64-byte message. */
    private val boxLen64 = hex(
        "5bb3_dcf4_6a6d_91c2_7356_80f1_8680_ac0c",
        "a731_423c_cf50_993d_5635_eea7_1e44_f50d",
        "576b_6f2f_23db_ad75_a046_7bb3_d596_c942",
        "fa5c_cba5_4f56_00a8_a78c_9723_9456_dbbd",
        "1874_e0ed_53ab_43de_a364_911d_3026_ea0d",
        "42ab_5e3c_1bfd_540d_d461_a43e_a64a_a910",
        "7590_ae12_a211_1e4e_8482_aa79_f1a0_476c",
    )

    /** A real box of a 100-byte message. */
    private val boxLen100 = hex(
        "4eb1_c8ae_6f35_91d6_642a_33fb_752c_144c",
        "d631_bea8_0b2f_ecec_97de_bba0_86b5_b56f",
        "fe51_7ca7_b030_c653_ed22_d5c5_ede8_16fb",
        "577d_ddb5_5b05_1e9f_ccfe_0507_571b_9322",
        "8b1a_f0d2_c576_66d4_7306_eabe_93a6_25c3",
        "05d3_7ee9_6e55_8cfa_1e73_1c17_8246_9bc0",
        "d089_a91e_230d_af9f_0131_8112_56d0_acf3",
        "9e81_56ce_17eb_1d12_dbb1_fa2d_58ef_9de5",
        "ea26_bc19_e783_93c9_5247_4c89_80b2_6954",
        "2e51_2551",
    )

    /**
     * The tag and ciphertext (32 bytes) of a box FORGED with an all-zero ephemeral key. That key
     * is a low-order point, so the shared secret is all zero for every recipient, and anyone can
     * make a box whose tag is correct. libsodium's own open refuses it.
     */
    private val forgedZeroEpkTail = hex(
        "186a_bc81_ff2f_2b1c_3ed3_5b0c_2619_421c",
        "a873_2c80_0f5b_d307_f2f6_c825_559a_895c",
    )

    /** The same kind of forged box, with the ephemeral key 1 (a second low-order point). */
    private val forgedOneEpkTail = hex(
        "c62e_b61e_8e63_1c01_0e95_1272_9acf_a8f5",
        "02e0_7667_394b_d74c_3936_f55f_5611_e95c",
    )

    /** Passes the real body cipher through and counts the calls, so a test can see whether it ran. */
    private class RecordingBodyCipher : BodyCipher {
        var calls = 0

        override fun apply(subkey: ByteArray, nonce24: ByteArray, body: ByteArray): ByteArray {
            calls++
            return DefaultBodyCipher.apply(subkey, nonce24, body)
        }
    }

    /** The message each box was made from. */
    private fun message(length: Int): ByteArray = ByteArray(length) { i -> ((i * 7 + 3) % 256).toByte() }

    private fun flipped(box: ByteArray, index: Int): ByteArray {
        val copy = box.copyOf()
        copy[index] = (copy[index].toInt() xor 1).toByte()
        return copy
    }

    /** Opens [box] with [opener]; a refusal fails the test with a message that says what broke. */
    private fun openOrFail(label: String, box: ByteArray, opener: SealedBoxOpener): ByteArray =
        try {
            opener.open(box, recipientPrivateKey)
        } catch (refused: CryptoRefused) {
            fail("crypto module: the $label box was refused but must open")
            throw refused
        }

    private fun assertOpens(label: String, box: ByteArray, expected: ByteArray) {
        val actual = openOrFail(label, box, SealedBoxOpener())

        assertArrayEquals("crypto module: the $label box must open to its message", expected, actual)
    }

    /** One refusal: the plain exception, no cause, and the body cipher never reached. */
    private fun assertRefused(what: String, box: ByteArray, key: ByteArray = recipientPrivateKey) {
        val recorder = RecordingBodyCipher()

        val e = assertThrows("crypto module: $what must be refused", CryptoRefused::class.java) {
            SealedBoxOpener(recorder).open(box, key)
        }

        assertEquals("crypto module: $what must give the plain refusal", CryptoRefused().message, e.message)
        assertNull("crypto module: $what must not carry a cause", e.cause)
        assertEquals("crypto module: $what must not reach the body cipher", 0, recorder.calls)
    }

    // --- real boxes open byte for byte ---------------------------------------------------------

    /** A failure means the empty-message box, exactly 48 bytes, did not open: the length rule is off by one. */
    @Test
    fun `a real sealed box of an empty message opens at exactly 48 bytes`() {
        assertEquals("crypto module: the empty-message box must be 48 bytes", 48, boxLen0.size)

        assertOpens("empty", boxLen0, message(0))
    }

    @Test
    fun `a real sealed box of a 1 byte message opens`() {
        assertOpens("1 byte", boxLen1, message(1))
    }

    @Test
    fun `a real sealed box of a 64 byte message opens`() {
        assertOpens("64 byte", boxLen64, message(64))
    }

    @Test
    fun `a real sealed box of a 100 byte message opens`() {
        assertOpens("100 byte", boxLen100, message(100))
    }

    /** A failure means open changed the box or the key it was given. */
    @Test
    fun `opening leaves the box and the key as they were`() {
        val box = boxLen64.copyOf()
        val key = recipientPrivateKey.copyOf()

        SealedBoxOpener().open(box, key)

        assertArrayEquals("crypto module: open must not change the box", boxLen64, box)
        assertArrayEquals("crypto module: open must not change the key", recipientPrivateKey, key)
    }

    // --- the body cipher runs once for a good box and never for a bad one ----------------------

    /** A failure means the good box did not reach the body cipher exactly once, so the probe below proves nothing. */
    @Test
    fun `a good box reaches the body cipher exactly once`() {
        val recorder = RecordingBodyCipher()

        val opened = openOrFail("64 byte", boxLen64, SealedBoxOpener(recorder))

        assertArrayEquals("crypto module: the good box must open through the recording cipher", message(64), opened)
        assertEquals("crypto module: the good box must reach the body cipher once", 1, recorder.calls)
    }

    // --- refusals ------------------------------------------------------------------------------

    @Test
    fun `one flipped bit in the tag is refused before the body cipher runs`() {
        assertRefused("a box with one tag bit flipped", flipped(boxLen64, 35))
    }

    @Test
    fun `one flipped bit in the tag of an empty message box is refused`() {
        assertRefused("an empty-message box with one tag bit flipped", flipped(boxLen0, 40))
    }

    @Test
    fun `one flipped bit in the ciphertext is refused before the body cipher runs`() {
        assertRefused("a box with one ciphertext bit flipped", flipped(boxLen64, 53))
    }

    @Test
    fun `one flipped bit in the ephemeral key is refused before the body cipher runs`() {
        assertRefused("a box with one ephemeral key bit flipped", flipped(boxLen64, 7))
    }

    @Test
    fun `a wrong recipient private key is refused before the body cipher runs`() {
        assertRefused("a box opened with another private key", boxLen64, wrongRecipientPrivateKey)
    }

    @Test
    fun `a box of 47 bytes is refused`() {
        assertRefused("a box of 47 bytes", boxLen0.copyOf(47))
    }

    @Test
    fun `a box of 31 bytes is refused`() {
        assertRefused("a box of 31 bytes", boxLen0.copyOf(31))
    }

    @Test
    fun `an empty box is refused`() {
        assertRefused("a box of 0 bytes", ByteArray(0))
    }

    @Test
    fun `a recipient key of 31 bytes is refused`() {
        assertRefused("a private key of 31 bytes", boxLen64, recipientPrivateKey.copyOf(31))
    }

    @Test
    fun `a recipient key of 33 bytes is refused`() {
        assertRefused("a private key of 33 bytes", boxLen64, recipientPrivateKey.copyOf(33))
    }

    /**
     * An all-zero ephemeral key makes the shared secret all zero. The tag of this box is
     * CORRECT for a zero shared secret, so no other rule can refuse it: only the library
     * throwing, or our own all-zero check, does. Either way the result is this refusal, and
     * this test is green whichever of the two acts.
     */
    @Test
    fun `a box with an all zero ephemeral key and a valid tag is refused`() {
        assertRefused("a box with an all-zero ephemeral key", ByteArray(32) + forgedZeroEpkTail)
    }

    /**
     * The check itself, on its own: if the library already throws for a zero shared secret, the
     * two boxes above cannot show that this check works, so it is pinned here. A failure means
     * it misses a set byte at some position, or a negative byte.
     */
    @Test
    fun `the all zero check sees a single set byte at any position`() {
        assertTrue("crypto module: 32 zero bytes must count as all zero", sealedAllZero(ByteArray(32)))
        for (i in 0 until 32) {
            val bytes = ByteArray(32)
            bytes[i] = 1
            assertFalse("crypto module: a set byte at position $i must not count as all zero", sealedAllZero(bytes))
        }
        val negative = ByteArray(32)
        negative[31] = 0x80.toByte()
        assertFalse("crypto module: a negative byte must not count as all zero", sealedAllZero(negative))
    }

    /** The same, with the ephemeral key 1, another low-order point. */
    @Test
    fun `a box with a low order ephemeral key of 1 and a valid tag is refused`() {
        assertRefused("a box with the ephemeral key 1", byteArrayOf(1) + ByteArray(31) + forgedOneEpkTail)
    }

    /** Joins the parts, drops the underscores that keep every digit run short, and reads hex. */
    private fun hex(vararg parts: String): ByteArray {
        val text = parts.joinToString("").replace("_", "")
        return ByteArray(text.length / 2) { i ->
            ((hexDigit(text[i * 2]) shl 4) or hexDigit(text[i * 2 + 1])).toByte()
        }
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
}
