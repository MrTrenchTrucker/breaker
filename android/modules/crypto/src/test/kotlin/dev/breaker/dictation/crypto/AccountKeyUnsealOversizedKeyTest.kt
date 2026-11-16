package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.SealedAgentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The data key given to unsealAndReencrypt must be exactly 32 bytes, and a key that is too LONG is
 * refused at the guard, before the sealed box is opened and before a nonce is asked. A key that is
 * too long is stopped later by the seal step in any case, but only after the opener has run and the
 * nonce source has been asked, so the order is what this file pins.
 */
class AccountKeyUnsealOversizedKeyTest : CryptoServiceTestFixture() {
    private val aad = aadFor(clientA, 1)

    /** Counts its calls and hands the box back as it is. */
    private class CountingOpener : BoxOpener {
        var calls = 0

        override fun open(box: ByteArray, recipientPrivateKey: ByteArray): ByteArray {
            calls++
            return box
        }
    }

    private fun assertRefusedBeforeAnyWork(keySize: Int) {
        val nonces = CountingNonces(nonceOne)
        val opener = CountingOpener()
        val service = AccountKeyServiceImpl(nonces, { size -> ByteArray(size) }, SealedBoxOpener(opener))
        val longKey = DataEncryptionKey(ByteArray(keySize) { 1 })

        val thrown = assertThrows(
            "crypto unseal: a data key of $keySize bytes must be refused",
            CryptoRefused::class.java,
        ) {
            service.unsealAndReencrypt(
                SealedAgentResult(hex(UnsealBoxData.validBoxHex)),
                BoxPrivateKey(hex(UnsealBoxData.recipientPrivateKeyHex)),
                longKey,
                aad,
            )
        }

        assertEquals("crypto unseal: refusal message for $keySize bytes", CryptoRefused().message, thrown.message)
        assertEquals("crypto unseal: no nonce is asked for a key of $keySize bytes", 0, nonces.calls)
        assertEquals("crypto unseal: the box is not opened for a key of $keySize bytes", 0, opener.calls)
    }

    @Test
    fun `a data key of 33 bytes is refused before the box is opened and before a nonce is asked`() =
        assertRefusedBeforeAnyWork(33)

    @Test
    fun `a data key of 64 bytes is refused before the box is opened and before a nonce is asked`() =
        assertRefusedBeforeAnyWork(64)
}
