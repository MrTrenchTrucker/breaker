package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.KeyEncryptionKey
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Closes two gaps of [AccountKeyServiceImpl]: the public key check must look at every one of the 32
 * bytes, and the default service must draw a fresh random nonce for every wrap.
 */
class AccountKeyServiceGapTest : CryptoServiceTestFixture() {
    private val saltBytes = ByteArray(16) { 7 }

    private fun service() = AccountKeyServiceImpl(CountingNonces(nonceOne)) { size -> ByteArray(size) }

    private fun flipBitAtIndex(bytes: ByteArray, index: Int): ByteArray {
        val copy = bytes.copyOf()
        copy[index] = (copy[index].toInt() xor 1).toByte()
        return copy
    }

    private fun allZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

    /** A failure here means the public key check ignores the last byte, or the first byte, of the key. */
    @Test
    fun `verifyBoxPublicKey refuses a flip in the last byte and in the first byte`() {
        val alice = CryptoVectorData.x25519.single { it.name == "rfc7748-6.1-alice" }
        val privateKey = BoxPrivateKey(hex(alice.privateHex))
        val publicKey = hex(alice.publicHex)

        service().verifyBoxPublicKey(privateKey, publicKey)

        refused("android_crypto: a public key with a flipped last byte was accepted") {
            service().verifyBoxPublicKey(privateKey, flipBitAtIndex(publicKey, 31))
        }
        refused("android_crypto: a public key with a flipped first byte was accepted") {
            service().verifyBoxPublicKey(privateKey, flipBitAtIndex(publicKey, 0))
        }
    }

    /**
     * A failure here means the default service repeats a nonce or uses an all zero nonce. The chance
     * that two honest random nonces are equal is 2^-96, and that one is all zero is 2^-96 too.
     */
    @Test
    fun `the default service gives a different non zero nonce to each wrap`() {
        val service = AccountKeyServiceImpl()
        val kek = KeyEncryptionKey(kekBytes)

        val first = service.wrapDek(dek, kek, saltBytes).bytes.copyOfRange(0, GCM_NONCE_BYTES)
        val other = DataEncryptionKey(patterned(0x20, 32))
        val second = service.wrapDek(other, kek, saltBytes).bytes.copyOfRange(0, GCM_NONCE_BYTES)
        val boxKey = BoxPrivateKey(patterned(0x10, 32))
        val third = service.wrapBoxPrivateKey(boxKey, dek).bytes.copyOfRange(0, GCM_NONCE_BYTES)

        assertFalse("android_crypto: wraps one and two share a nonce", first.contentEquals(second))
        assertFalse("android_crypto: wraps one and three share a nonce", first.contentEquals(third))
        assertFalse("android_crypto: wraps two and three share a nonce", second.contentEquals(third))
        for (nonce in listOf(first, second, third)) {
            assertFalse("android_crypto: a nonce was all zero bytes", allZero(nonce))
        }
    }
}
