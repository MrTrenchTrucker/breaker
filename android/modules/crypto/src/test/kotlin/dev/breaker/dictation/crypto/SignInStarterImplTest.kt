package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.SignInAttempt
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SignInStarterImpl]: the one-time secret text, the X25519 key pair it makes and the
 * zeroing of the temporary arrays it takes from its entropy source.
 *
 * Most tests feed the starter known bytes, so every value can be checked by hand. Only the test
 * that builds a starter with the default source reads the real random source.
 */
class SignInStarterImplTest {
    private val zeros = ByteArray(32)
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" + "01234" + "56789-_"
    private val wrongSizeMessage = "The entropy source returned the wrong number of bytes"
    private val knownChallenge = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
    private val typedText = "Zk3_x-Q9aB7" + "cD1eF5gH8iJ" + "2kL4mN6oP0r" + "S3tU5vW7xY"
    private val typedChallenge = "h0MHWX2Mnc_cEYThDk9bpQ7-lifZvd4YEyafEtXRNd4"
    private val challengeOverDecodedBytes = "Z3JoK-TIBKY_twhgk1gQsMYx8vzsapyVqErlBYOpgF4"
    private val typedBytes = hex(
        "664d_ffc7_e43d_681e_dc0f_5785_e601_fc88",
        "9da4_2f89_8dea_83f4_ad2d_ed53_9bd6_ef16",
    )
    private val alicePrivate = hex(
        "7707_6d0a_7318_a57d",
        "3c16_c172_51b2_6645",
        "df4c_2f87_ebc0_992a",
        "b177_fba5_1db9_2c2a",
    )
    private val alicePublic = hex(
        "8520_f009_8930_a754",
        "748b_7ddc_b43e_f75a",
        "0dbf_3a0d_2638_1af4",
        "eba4_a98e_aa9b_4e6a",
    )

    /** Hands out the given arrays in order. It keeps what was asked, the arrays it handed out, and a copy of each taken at hand-out. */
    private class Feed(vararg values: ByteArray) {
        private val queue = values.map { it.copyOf() }
        val sizesAsked = mutableListOf<Int>()
        val handedOut = mutableListOf<ByteArray>()
        val copiesAtHandOut = mutableListOf<ByteArray>()

        fun entropy(size: Int): ByteArray {
            sizesAsked.add(size)
            val next = queue[sizesAsked.size - 1].copyOf()
            handedOut.add(next)
            copiesAtHandOut.add(next.copyOf())
            return next
        }
    }

    private fun starterFor(feed: Feed) = SignInStarterImpl(feed::entropy)

    private fun secretTextOf(attempt: SignInAttempt): String =
        String(attempt.copySecretBytes(), Charsets.US_ASCII)

    private fun allZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

    private fun alphabetOnly(text: String): Boolean =
        text.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' }

    private fun lastCharacterCanonical(text: String): Boolean = alphabet.indexOf(text.last()) % 4 == 0

    private fun decodesAndEncodesBack(text: String): Boolean =
        Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(text + "=")) == text

    private fun checkSecretShape(text: String) {
        assertEquals("crypto: the secret text must be forty three characters", 43, text.length)
        assertTrue("crypto: the secret text has a character outside the alphabet", alphabetOnly(text))
        assertTrue("crypto: the last character of the secret text is not canonical", lastCharacterCanonical(text))
        assertTrue("crypto: the secret text does not decode and encode back to itself", decodesAndEncodesBack(text))
    }

    private fun refusedWithFixedMessage(label: String, feed: Feed) {
        val refusal = assertThrows("crypto: $label", IllegalArgumentException::class.java) {
            starterFor(feed).begin()
        }
        assertEquals("crypto: the refusal must carry the fixed message", wrongSizeMessage, refusal.message)
    }

    @Test
    fun `the zero entropy gives forty three A characters as the secret text`() {
        val attempt = starterFor(Feed(zeros, alicePrivate)).begin()

        assertEquals("crypto: zero entropy must give forty three A characters", "A".repeat(43), secretTextOf(attempt))
    }

    @Test
    fun `the zero entropy gives the known challenge over the ascii text`() {
        val attempt = starterFor(Feed(zeros, alicePrivate)).begin()

        assertEquals("crypto: the challenge is not the known answer for the A text", knownChallenge, attempt.challenge)
    }

    @Test
    fun `the second entropy array is the x25519 private key and gives the rfc public key`() {
        val attempt = starterFor(Feed(zeros, alicePrivate)).begin()

        assertArrayEquals("crypto: the private key is not the second entropy array", alicePrivate, attempt.privateKey.copyBytes())
        assertArrayEquals("crypto: the public key is not the RFC 7748 public key", alicePublic, attempt.copyPublicKey())
    }

    @Test
    fun `the entropy function is called twice with thirty two bytes each in order`() {
        val feed = Feed(zeros, alicePrivate)

        starterFor(feed).begin()

        assertEquals("crypto: the entropy function must be called exactly twice", listOf(32, 32), feed.sizesAsked)
        assertArrayEquals("crypto: the second call must supply the private key", alicePrivate, feed.copiesAtHandOut[1])
    }

    @Test
    fun `the second vector gives its challenge over the ascii text and not over the decoded bytes`() {
        val attempt = starterFor(Feed(typedBytes, alicePrivate)).begin()

        assertEquals("crypto: the secret text is not the second vector", typedText, secretTextOf(attempt))
        assertEquals("crypto: the challenge is not the hash of the ascii text", typedChallenge, attempt.challenge)
        assertFalse("crypto: the challenge is the hash of the decoded bytes", challengeOverDecodedBytes == attempt.challenge)
    }

    @Test
    fun `the starter zero fills the entropy arrays it was handed after a begin`() {
        val feed = Feed(typedBytes, alicePrivate)

        starterFor(feed).begin()

        assertFalse("crypto: the first array was zero before the begin", allZero(feed.copiesAtHandOut[0]))
        assertFalse("crypto: the second array was zero before the begin", allZero(feed.copiesAtHandOut[1]))
        assertTrue("crypto: the first entropy array was left in memory", allZero(feed.handedOut[0]))
        assertTrue("crypto: the second entropy array was left in memory", allZero(feed.handedOut[1]))
    }

    @Test
    fun `an entropy array of thirty one or thirty three bytes is refused with the fixed message`() {
        for (size in intArrayOf(31, 33)) {
            val wrong = ByteArray(size) { 0x55 }

            val firstFeed = Feed(wrong, alicePrivate)
            refusedWithFixedMessage("a first array of $size bytes must be refused", firstFeed)
            assertTrue("crypto: the refused first array was left in memory", allZero(firstFeed.handedOut[0]))

            val secondFeed = Feed(zeros, wrong)
            refusedWithFixedMessage("a second array of $size bytes must be refused", secondFeed)
            assertTrue("crypto: the refused second array was left in memory", allZero(secondFeed.handedOut[1]))
        }

        val control = starterFor(Feed(zeros, alicePrivate)).begin()
        assertEquals("crypto: the right size must be accepted as the control", 43, secretTextOf(control).length)
    }

    @Test
    fun `a refusal message and the attempt text never hold the secret text or the challenge`() {
        val refusal = assertThrows("crypto: a short key array must be refused", IllegalArgumentException::class.java) {
            starterFor(Feed(zeros, ByteArray(31) { 0x41 })).begin()
        }
        assertFalse("crypto: the refusal message holds the secret text", refusal.message.orEmpty().contains("AAAA"))

        val text = starterFor(Feed(zeros, alicePrivate)).begin().toString()
        assertFalse("crypto: the attempt text holds the challenge", text.contains(knownChallenge))
        assertFalse("crypto: the attempt text holds the secret text", text.contains("AAAA"))
    }

    @Test
    fun `the default starter gives a fresh secret and key on each of two begins`() {
        val starter = SignInStarterImpl()
        val first = starter.begin()
        val second = starter.begin()

        assertFalse("crypto: two begins gave the same secret text", secretTextOf(first) == secretTextOf(second))
        assertFalse(
            "crypto: two begins gave the same private key",
            first.privateKey.copyBytes().contentEquals(second.privateKey.copyBytes()),
        )
        assertFalse("crypto: two begins gave the same public key", first.copyPublicKey().contentEquals(second.copyPublicKey()))
        checkSecretShape(secretTextOf(first))
        checkSecretShape(secretTextOf(second))
    }

    @Test
    fun `the secret text is canonical over sixty four fixed runs`() {
        for (run in 0 until 64) {
            val first = ByteArray(32) { i -> if (i == 31) (run * 4).toByte() else (run + i).toByte() }
            val key = ByteArray(32) { i -> (run * 3 + i).toByte() }

            checkSecretShape(secretTextOf(starterFor(Feed(first, key)).begin()))
        }
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
        else -> throw IllegalArgumentException("not a hex digit")
    }
}
