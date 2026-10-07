package dev.breaker.dictation.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * splitKeys turns the 32-byte Argon2id output into the key-encryption key and the
 * login verifier with HKDF-SHA256: an empty salt, the label as ASCII text for the
 * Expand info, and 32 bytes each. The two labels are what keep the two values
 * unrelated, so they are pinned here as literal text.
 *
 * Expected values were computed with an independent HKDF implementation (the input
 * is the 32 bytes 00 01 02 and so on up to 1f), cross-checked with a second one.
 */
class KeySplitTest {

    private val expectedKek = "64ab835ac3c4c27e630723be2516fd9fca90ec58ade981222aa0219812915995"
    private val expectedVerifier = "69503b3190a0704fca87cd50ee5819dc592fe5ab476416aff2e9408cff83a4e8"

    private fun input() = ByteArray(32) { it.toByte() }

    /**
     * A failure means the key-encryption key differs from the independent value: wrong label, salt,
     * hash or length.
     */
    @Test
    fun `split of the counting input gives the expected key-encryption key`() {
        val keys = splitKeys(input())
        assertEquals("key-encryption key", expectedKek, keys.kek.copyBytes().toHex())
    }

    /** A failure means the verifier differs from the independent value: wrong label, salt, hash or length. */
    @Test
    fun `split of the counting input gives the expected verifier`() {
        val keys = splitKeys(input())
        assertEquals("verifier", expectedVerifier, keys.authVerifier.copyBytes().toHex())
    }

    /** A failure means a label was renamed, which would change every key and verifier ever derived. */
    @Test
    fun `labels are the two literal strings`() {
        assertEquals("key label", "breaker-kek-v1", KEK_LABEL)
        assertEquals("verifier label", "breaker-auth-verifier-v1", AUTH_VERIFIER_LABEL)
    }

    /** A failure means both parts came from the same label, or a part is just a copy of the input. */
    @Test
    fun `key and verifier differ from each other and from the input`() {
        val keys = splitKeys(input())
        val kek = keys.kek.copyBytes().toHex()
        val verifier = keys.authVerifier.copyBytes().toHex()
        assertNotEquals("key equals verifier", kek, verifier)
        assertNotEquals("key equals the input", input().toHex(), kek)
        assertNotEquals("verifier equals the input", input().toHex(), verifier)
    }

    /** A failure means one of the two parts is not 32 bytes long. */
    @Test
    fun `key and verifier are 32 bytes each`() {
        val keys = splitKeys(input())
        assertEquals("key length", 32, keys.kek.copyBytes().size)
        assertEquals("verifier length", 32, keys.authVerifier.copyBytes().size)
    }

    /** A failure means the split changed the array it was given (for example by wiping it). */
    @Test
    fun `input array is unchanged by the split`() {
        val given = input()
        splitKeys(given)
        assertArrayEquals("the input array was modified", input(), given)
    }

    /** A failure means the split depends on something other than its input. */
    @Test
    fun `two splits of the same input give equal bytes`() {
        val first = splitKeys(input())
        val second = splitKeys(input())
        assertArrayEquals("key differs between calls", first.kek.copyBytes(), second.kek.copyBytes())
        assertArrayEquals(
            "verifier differs between calls",
            first.authVerifier.copyBytes(),
            second.authVerifier.copyBytes(),
        )
    }
}
