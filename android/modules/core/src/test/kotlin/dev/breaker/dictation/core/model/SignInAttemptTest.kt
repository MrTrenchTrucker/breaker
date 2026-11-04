package dev.breaker.dictation.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The sign-in attempt: the one-time secret text, the private key made for one sign-in, the
 * public key and the challenge. Every size and character rule is checked at both edges, and
 * every refusal is checked while all the other parts are valid, so the refusal is the only
 * reason the attempt fails.
 */
class SignInAttemptTest {
    private val challengeText = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"
    private val textRefusal = "The sign-in secret text must be 43 URL-safe characters with a canonical last character"
    private val keyRefusal = "The sign-in public key must be 32 bytes"
    private val challengeRefusal = "The sign-in challenge must be 43 URL-safe characters"

    /** The RFC 7748 section 6.1 public key of Alice, written as grouped hex. */
    private fun publicKey(): ByteArray = hex(
        "8520_f009_8930_a754_748b_7ddc_b43e_f75a",
        "0dbf_3a0d_2638_1af4_eba4_a98e_aa9b_4e6a",
    )

    private fun aSecret(): ByteArray = ByteArray(43) { 'A'.code.toByte() }

    private fun secretEndingWith(last: Char): ByteArray = ByteArray(43) { i -> (if (i == 42) last else 'A').code.toByte() }

    private fun attemptFrom(
        secretBytes: ByteArray = aSecret(),
        publicBytes: ByteArray = publicKey(),
        challenge: String = challengeText,
    ): SignInAttempt = SignInAttempt(secretBytes, SignInPrivateKey(ByteArray(32) { 0x11.toByte() }), publicBytes, challenge)

    /** Runs the block and returns the message of the refusal it throws. A block that is accepted fails the test. */
    private fun refusal(block: () -> Unit): String {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return expected.message.orEmpty()
        }
        fail("the attempt was accepted but must be refused")
        return ""
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

    @Test
    fun `an attempt keeps the secret text and the public key it was given`() {
        val attempt = attemptFrom()

        assertArrayEquals("the secret text copy is not the 43 A text", aSecret(), attempt.copySecretBytes())
        assertArrayEquals("the public key copy is not the RFC vector", publicKey(), attempt.copyPublicKey())
        assertEquals("the challenge changed", challengeText, attempt.challenge)
    }

    @Test
    fun `a secret of 42 or 44 bytes is refused and 43 is accepted`() {
        for (size in listOf(42, 44)) {
            val message = refusal { attemptFrom(secretBytes = ByteArray(size) { 'A'.code.toByte() }) }
            assertEquals("a $size byte secret text must be refused with the fixed message", textRefusal, message)
        }
        assertEquals(43, attemptFrom().copySecretBytes().size)
    }

    @Test
    fun `a secret whose last character is not canonical is refused`() {
        for (last in listOf('A', 'E', 'w', '0', '8')) {
            assertEquals("the canonical last character $last must be accepted", 43, attemptFrom(secretBytes = secretEndingWith(last)).copySecretBytes().size)
        }
        for (last in listOf('B', 'D', 'z', '1', '9', '-', '_')) {
            assertEquals("the non canonical last character $last must be refused", textRefusal, refusal { attemptFrom(secretBytes = secretEndingWith(last)) })
        }
    }

    @Test
    fun `a secret byte outside the URL-safe alphabet is refused and the two marks inside it are accepted`() {
        for (bad in listOf('+', '/', '=', ' ', '!', '.')) {
            val bytes = ByteArray(43) { i -> (if (i == 0) bad else 'A').code.toByte() }
            assertEquals("the character $bad must be refused", textRefusal, refusal { attemptFrom(secretBytes = bytes) })
        }
        val marks = ByteArray(43) { i -> (if (i == 5) '-' else if (i == 6) '_' else 'A').code.toByte() }
        assertEquals(43, attemptFrom(secretBytes = marks).copySecretBytes().size)
    }

    @Test
    fun `a public key of 31 or 33 bytes is refused and 32 bytes is accepted`() {
        for (size in listOf(31, 33)) {
            assertEquals("a $size byte public key must be refused", keyRefusal, refusal { attemptFrom(publicBytes = ByteArray(size) { 0x5A.toByte() }) })
        }
        assertEquals(32, attemptFrom(publicBytes = ByteArray(32) { 0x5A.toByte() }).copyPublicKey().size)
    }

    @Test
    fun `a challenge of 42 or 44 characters or with a bad character is refused`() {
        val bad = listOf(challengeText.dropLast(1), challengeText + "A", "+" + challengeText.drop(1))
        for (text in bad) {
            assertEquals("a bad challenge must be refused", challengeRefusal, refusal { attemptFrom(challenge = text) })
        }
        assertEquals(challengeText, attemptFrom(challenge = challengeText).challenge)
    }

    @Test
    fun `the attempt copies the arrays it is given`() {
        val secretBytes = aSecret()
        val publicBytes = publicKey()
        val attempt = attemptFrom(secretBytes = secretBytes, publicBytes = publicBytes)

        secretBytes.fill(0x7A.toByte())
        publicBytes.fill(0x01.toByte())

        assertArrayEquals("the attempt changed when the caller changed its secret array", aSecret(), attempt.copySecretBytes())
        assertArrayEquals("the attempt changed when the caller changed its public array", publicKey(), attempt.copyPublicKey())
    }

    @Test
    fun `each read hands out a new copy and an edited copy changes nothing`() {
        val attempt = attemptFrom()
        val first = attempt.copySecretBytes()
        val second = attempt.copySecretBytes()
        assertNotSame("the same array came back twice", first, second)

        first[0] = 0x7A.toByte()
        second.fill(0)
        assertArrayEquals("an edited copy changed the attempt", aSecret(), attempt.copySecretBytes())

        val key = attempt.copyPublicKey()
        key.fill(0)
        assertArrayEquals("an edited public key copy changed the attempt", publicKey(), attempt.copyPublicKey())
    }

    @Test
    fun `wipe zeroes the secret copy and the public key and the private key`() {
        val attempt = attemptFrom()
        val before = attempt.copySecretBytes()
        assertFalse("the secret text must be non-zero before wipe", before.all { it == 0.toByte() })
        assertFalse("the private key must be non-zero before wipe", attempt.privateKey.copyBytes().all { it == 0.toByte() })

        attempt.wipe()

        assertArrayEquals("wipe left the secret text unzeroed", ByteArray(43), attempt.copySecretBytes())
        assertArrayEquals("wipe left the public key unzeroed", ByteArray(32), attempt.copyPublicKey())
        assertArrayEquals("wipe left the private key unzeroed", ByteArray(32), attempt.privateKey.copyBytes())
        assertArrayEquals("a copy taken before wipe is the caller's and must keep its bytes", aSecret(), before)
    }

    @Test
    fun `wipe is idempotent and isWiped turns true only after wipe`() {
        val attempt = attemptFrom()
        assertFalse("a new attempt reports wiped", attempt.isWiped)

        attempt.wipe()
        attempt.wipe()

        assertTrue("the attempt does not report wiped", attempt.isWiped)
        assertArrayEquals("a second wipe changed the zeroed secret", ByteArray(43), attempt.copySecretBytes())
    }

    @Test
    fun `the private key is the one given and is not copied`() {
        val key = SignInPrivateKey(ByteArray(32) { 0x11.toByte() })
        val attempt = SignInAttempt(aSecret(), key, publicKey(), challengeText)

        assertSame("the attempt copied its private key", key, attempt.privateKey)
        attempt.wipe()
        assertArrayEquals("wiping the attempt did not wipe its private key", ByteArray(32), key.copyBytes())
    }

    @Test
    fun `toString is the fixed text before and after wipe`() {
        val attempt = attemptFrom()
        assertEquals("SignInAttempt(redacted)", attempt.toString())
        assertFalse("toString shows the challenge", attempt.toString().contains(challengeText))

        attempt.wipe()

        assertEquals("SignInAttempt(redacted)", attempt.toString())
    }

    @Test
    fun `two attempts with the same values are different objects`() {
        assertNotEquals("attempts with the same values compare equal", attemptFrom(), attemptFrom())
    }
}
