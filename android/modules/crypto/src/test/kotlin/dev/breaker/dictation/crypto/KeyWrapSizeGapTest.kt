package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.WrappedBoxPrivateKey
import dev.breaker.dictation.core.model.WrappedPreviousDek
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Closes the size gaps of the key wrap: a key to wrap that is not 32 bytes, a wrapping key that is
 * not 32 bytes (although AES would take 16 and 24), and a wrapped blob that is 59 or 61 bytes with a
 * correct tag, so that only the size rule can refuse it.
 */
class KeyWrapSizeGapTest : CryptoServiceTestFixture() {
    private val saltBytes = ByteArray(16) { 7 }

    private fun service() = AccountKeyServiceImpl(CountingUpNonces(0x30)) { size -> ByteArray(size) }

    /** Fails the test unless the block throws exactly the plain refusal, with no subclass. */
    private fun refusedExactly(message: String, block: () -> Unit) {
        val thrown = assertThrows(message, Throwable::class.java) { block() }
        assertEquals(message, CryptoRefused::class.java, thrown.javaClass)
    }

    /**
     * A blob made for [aad] of the given plain size: the nonce, then the sealed bytes with a correct
     * tag. It checks by the cipher that the blob is genuine, so only the size rule can refuse it.
     */
    private fun genuineBlob(plainSize: Int, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = patterned(0x70, GCM_NONCE_BYTES)
        val plain = patterned(0x20, plainSize)
        val blob = nonce + sealBytes(plain, key, aad, nonce)
        assertEquals("android_crypto: control, the blob size", plainSize + 28, blob.size)
        val opened = openBytes(blob.copyOfRange(GCM_NONCE_BYTES, blob.size), key, aad, nonce)
        assertArrayEquals("android_crypto: control, the cipher opens the blob", plain, opened)
        return blob
    }

    /** A failure here means a data key that is not 32 bytes was wrapped as the key or the previous key. */
    @Test
    fun `a data key of 16 or 33 bytes is refused in wrapDek and wrapPreviousDek`() {
        val kek = KeyEncryptionKey(kekBytes)
        val newDek = DataEncryptionKey(patterned(0xC0, 32))
        for (size in intArrayOf(16, 33)) {
            val plain = DataEncryptionKey(patterned(0x20, size))
            refusedExactly("android_crypto: a $size byte key was wrapped by wrapDek") {
                service().wrapDek(plain, kek, saltBytes)
            }
            refusedExactly("android_crypto: a $size byte key was wrapped by wrapPreviousDek") {
                service().wrapPreviousDek(plain, newDek)
            }
        }
        val good = DataEncryptionKey(patterned(0x20, 32))
        val wrappedDek = service().wrapDek(good, kek, saltBytes)
        assertEquals("android_crypto: control, wrapDek of 32 bytes", 60, wrappedDek.bytes.size)
        val wrappedPrev = service().wrapPreviousDek(good, newDek)
        assertEquals("android_crypto: control, wrapPreviousDek of 32 bytes", 60, wrappedPrev.bytes.size)
    }

    /** A failure here means a wrapping key of 16 or 24 bytes, valid for AES, was accepted. */
    @Test
    fun `a wrapping key of 16 or 24 bytes is refused in wrapBoxPrivateKey and wrapPreviousDek`() {
        val boxKey = BoxPrivateKey(patterned(0x10, 32))
        val previous = DataEncryptionKey(patterned(0x20, 32))
        for (size in intArrayOf(16, 24)) {
            val shortKey = DataEncryptionKey(patterned(0xC0, size))
            refusedExactly("android_crypto: a $size byte key wrapped the box key") {
                service().wrapBoxPrivateKey(boxKey, shortKey)
            }
            refusedExactly("android_crypto: a $size byte key wrapped the previous key") {
                service().wrapPreviousDek(previous, shortKey)
            }
        }
        val good = DataEncryptionKey(patterned(0xC0, 32))
        val wrappedBox = service().wrapBoxPrivateKey(boxKey, good)
        assertEquals("android_crypto: control, wrapBoxPrivateKey under 32 bytes", 60, wrappedBox.bytes.size)
        val wrappedPrev = service().wrapPreviousDek(previous, good)
        assertEquals("android_crypto: control, wrapPreviousDek under 32 bytes", 60, wrappedPrev.bytes.size)
    }

    /** A failure here means openKey or unwrapPreviousDek accepted a 59 or 61 byte blob with a good tag. */
    @Test
    fun `a genuine blob of 59 or 61 bytes is refused by openKey and by unwrapPreviousDek`() {
        val boxAad = aadBytes(BOX_PRIVATE_KEY_AAD_TEXT)
        val prevAad = aadBytes(PREVIOUS_DEK_AAD_TEXT)
        val control = genuineBlob(32, dekBytes, boxAad)
        val controlOpened = openKey(control, dekBytes, boxAad)
        assertArrayEquals("android_crypto: control, 60 bytes open", patterned(0x20, 32), controlOpened)
        for (size in intArrayOf(31, 33)) {
            val boxBlob = genuineBlob(size, dekBytes, boxAad)
            refusedExactly("android_crypto: openKey took a ${boxBlob.size} byte blob") {
                openKey(boxBlob, dekBytes, boxAad)
            }
            val prevBlob = genuineBlob(size, dekBytes, prevAad)
            refusedExactly("android_crypto: unwrapPreviousDek took a ${prevBlob.size} byte blob") {
                service().unwrapPreviousDek(WrappedPreviousDek(prevBlob), dek)
            }
        }
    }

    /**
     * A failure here means unwrapBoxPrivateKey accepted a 59 or 61 byte blob. This is a contract check
     * only: without the size rule the blob opens to 31 or 33 bytes and the BoxPrivateKey constructor
     * refuses that size, so this path cannot tell the size rule from its absence.
     */
    @Test
    fun `a genuine blob of 59 or 61 bytes is refused by unwrapBoxPrivateKey`() {
        val boxAad = aadBytes(BOX_PRIVATE_KEY_AAD_TEXT)
        for (size in intArrayOf(31, 33)) {
            val blob = genuineBlob(size, dekBytes, boxAad)
            refusedExactly("android_crypto: unwrapBoxPrivateKey took a ${blob.size} byte blob") {
                service().unwrapBoxPrivateKey(WrappedBoxPrivateKey(blob), dek)
            }
        }
    }
}
