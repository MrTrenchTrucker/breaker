package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.seal.SealRefused
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The phone's opener turns every refusal of the shared sealed-box module into the phone's one plain
 * [CryptoRefused]. The unseal service wraps ANY failure into CryptoRefused itself, so a test through
 * the service cannot see a leaked SealRefused; these tests call [SealedBoxOpener] directly and
 * check the exact class, so the wrapper's mapping is pinned on its own.
 */
class SealedBoxOpenerRefusalTest {
    private val key = ByteArray(32) { 7 }

    private fun assertExactlyCryptoRefused(label: String, block: () -> Unit) {
        val thrown = assertThrows("crypto opener: $label must be refused", Throwable::class.java) { block() }
        assertEquals(
            "crypto opener: $label must be exactly CryptoRefused, not the shared module's SealRefused",
            CryptoRefused::class.java,
            thrown.javaClass,
        )
        assertEquals("crypto opener: $label message", CryptoRefused().message, thrown.message)
        assertNull("crypto opener: $label must have no cause", thrown.cause)
    }

    @Test
    fun `a garbage box the shared opener refuses is refused as exactly CryptoRefused`() {
        assertExactlyCryptoRefused("garbage box of 64 bytes") { SealedBoxOpener().open(ByteArray(64) { 1 }, key) }
    }

    @Test
    fun `a box shorter than 48 bytes is refused as exactly CryptoRefused`() {
        assertExactlyCryptoRefused("box of 47 bytes") { SealedBoxOpener().open(ByteArray(47) { 1 }, key) }
    }

    @Test
    fun `a key that is not 32 bytes is refused as exactly CryptoRefused`() {
        assertExactlyCryptoRefused("key of 31 bytes") { SealedBoxOpener().open(ByteArray(64) { 1 }, ByteArray(31)) }
    }

    @Test
    fun `a SealRefused from the seam is mapped to exactly CryptoRefused`() {
        val refusing = BoxOpener { _, _ -> throw SealRefused() }
        assertExactlyCryptoRefused("SealRefused from the seam") { SealedBoxOpener(refusing).open(ByteArray(64) { 1 }, key) }
    }
}
