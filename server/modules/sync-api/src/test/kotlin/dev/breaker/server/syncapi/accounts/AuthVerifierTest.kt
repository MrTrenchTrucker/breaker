package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.accounts.AccountFixtures.hex
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

internal class AuthVerifierTest {

    private fun zeroToThirtyOne(): ByteArray = ByteArray(32) { index -> index.toByte() }

    @Test
    fun `fromBytes refuses every length except 32`() {
        for (length in listOf(0, 1, 31, 33, 64)) {
            assertNull("length $length must be refused", AuthVerifier.fromBytes(ByteArray(length)))
        }
    }

    @Test
    fun `fromBytes accepts exactly 32 bytes`() {
        assertNotNull("length 32 must be accepted", AuthVerifier.fromBytes(ByteArray(32)))
        assertEquals("the length constant", 32, AuthVerifier.LENGTH)
    }

    @Test
    fun `copyBytes returns the bytes that were given`() {
        val verifier = checkNotNull(AuthVerifier.fromBytes(zeroToThirtyOne()))
        assertArrayEquals("copyBytes must equal the input", zeroToThirtyOne(), verifier.copyBytes())
    }

    @Test
    fun `changing the array passed to fromBytes does not change the verifier`() {
        val input = zeroToThirtyOne()
        val verifier = checkNotNull(AuthVerifier.fromBytes(input))
        input[0] = 99
        assertArrayEquals("the verifier must hold its own copy", zeroToThirtyOne(), verifier.copyBytes())
    }

    @Test
    fun `changing an array returned by copyBytes does not change the verifier`() {
        val verifier = checkNotNull(AuthVerifier.fromBytes(zeroToThirtyOne()))
        val first = verifier.copyBytes()
        first[5] = 99
        assertArrayEquals("copyBytes must hand out a fresh copy each time", zeroToThirtyOne(), verifier.copyBytes())
    }

    @Test
    fun `toString says redacted and nothing else`() {
        val verifier = checkNotNull(AuthVerifier.fromBytes(zeroToThirtyOne()))
        assertEquals("toString", "AuthVerifier(redacted)", verifier.toString())
    }

    @Test
    fun `no text form of the verifier shows its bytes`() {
        val verifier = checkNotNull(AuthVerifier.fromBytes(zeroToThirtyOne()))
        val bytes = zeroToThirtyOne()
        val forbidden = mapOf(
            "hex" to hex(bytes),
            "base64" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            "decimal list" to "[0, 1, 2",
        )
        // The literal base64 above must be the real encoding, or the check would pass on a typo.
        assertEquals("base64 literal", Base64.getEncoder().encodeToString(bytes), forbidden.getValue("base64"))
        val texts = listOf(verifier.toString(), "$verifier", listOf(verifier).toString())
        for (text in texts) {
            for ((form, needle) in forbidden) {
                assertFalse("the $form form of the bytes leaked into '$text'", text.contains(needle))
            }
        }
    }
}
