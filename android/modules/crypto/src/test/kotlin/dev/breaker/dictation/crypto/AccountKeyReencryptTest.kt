package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves [AccountKeyServiceImpl.reencrypt]: a ciphertext is opened under the old key and encrypted
 * again byte for byte under the new key, every refusal is the one plain [CryptoRefused], and the
 * nonce source is never asked on a refusal.
 *
 * The test makes and opens its ciphertexts with the platform cipher directly, so it does not depend
 * on the code under test. The zeroing of the opened plaintext is not observable from a test: the
 * array is made and zeroed inside the function and there is no seam, so the Python text test
 * (`test_reencrypt_limits.py`) guards the `fill(0)`.
 */
class AccountKeyReencryptTest : CryptoServiceTestFixture() {
    private val keyA = dek
    private val keyBBytes = patterned(0x30, 32)
    private val keyB = DataEncryptionKey(keyBBytes)
    private val aadA = aadFor(clientA, 1)
    private val aadB = aadFor(clientB, 2)
    private val text = "hello from the truck"
    private val textBytes = text.toByteArray(Charsets.UTF_8)

    /** The service under test, with the given nonce source and an entropy source it never uses. */
    private fun service(nonces: NonceSource) =
        AccountKeyServiceImpl(nonces, { size -> ByteArray(size) }, SealedBoxOpener())

    /** One AES-256-GCM operation through the platform cipher; on decryption [input] ends with the tag. */
    private fun gcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    /** Encrypts [plain] bytes without any String, and splits the result into ciphertext and tag. */
    private fun encryptIndependently(
        plain: ByteArray,
        key: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
    ): CipherText {
        val sealed = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plain)
        val tagStart = sealed.size - 16
        return CipherText(
            sealed.copyOfRange(0, tagStart),
            nonce.copyOf(),
            sealed.copyOfRange(tagStart, sealed.size),
        )
    }

    /** Opens [cipherText] with the platform cipher and returns the plaintext bytes. */
    private fun openIndependently(cipherText: CipherText, key: ByteArray, aad: ByteArray): ByteArray =
        gcm(Cipher.DECRYPT_MODE, key, cipherText.nonce, aad, cipherText.ciphertext + cipherText.tag)

    /** The ciphertext of the sample text under key A and AAD A. */
    private fun original(): CipherText = encryptIndependently(textBytes, keyA.bytes, aadA, nonceTwo)

    private fun flipBit(bytes: ByteArray, index: Int): ByteArray {
        val copy = bytes.copyOf()
        copy[index] = (copy[index].toInt() xor 1).toByte()
        return copy
    }

    /** The plain refusal: exact class, the plain message, no cause. */
    private fun assertPlainRefusal(label: String, block: () -> Unit) {
        val thrown = assertThrows("crypto reencrypt: $label must be refused", Throwable::class.java) {
            block()
        }
        assertEquals(
            "crypto reencrypt: $label must be a plain CryptoRefused",
            CryptoRefused::class.java,
            thrown.javaClass,
        )
        assertEquals("crypto reencrypt: $label message", CryptoRefused().message, thrown.message)
        assertNull("crypto reencrypt: $label must have no cause", thrown.cause)
    }

    /** One refusal before the nonce is made: the plain refusal and the nonce source asked zero times. */
    private fun assertRefusedWithNoNonce(
        label: String,
        input: CipherText,
        oldKey: DataEncryptionKey = keyA,
        newKey: DataEncryptionKey = keyB,
        oldAad: ByteArray = aadA,
    ) {
        val nonces = CountingNonces(nonceOne)
        assertPlainRefusal(label) {
            service(nonces).reencrypt(input, oldKey, oldAad, newKey, aadB)
        }
        assertEquals("crypto reencrypt: $label must not ask for a nonce", 0, nonces.calls)
    }

    /** A nonce source that hands out 11 bytes and counts how often it was asked. */
    private class ShortNonces : NonceSource {
        var calls = 0

        override fun next(): ByteArray {
            calls++
            return ByteArray(11)
        }
    }

    // --- (1) the round trip --------------------------------------------------------------------

    @Test
    fun `a ciphertext is opened under the old key and encrypted under the new key`() {
        val nonces = CountingNonces(nonceOne)

        val result = service(nonces).reencrypt(original(), keyA, aadA, keyB, aadB)

        val decrypted = CryptoServiceImpl().decrypt(result, keyB, aadB)
        assertEquals("crypto reencrypt: decrypts under the new key", text, decrypted)
        assertArrayEquals(
            "crypto reencrypt: independent open gives the same bytes",
            textBytes,
            openIndependently(result, keyBBytes, aadB),
        )
        assertEquals("crypto reencrypt: success asks one nonce", 1, nonces.calls)
        assertArrayEquals("crypto reencrypt: result nonce is the source nonce", nonceOne, result.nonce)
        assertEquals("crypto reencrypt: tag size", 16, result.tag.size)
        assertEquals("crypto reencrypt: ciphertext size", textBytes.size, result.ciphertext.size)
        refused("crypto reencrypt: result under the old key") {
            CryptoServiceImpl().decrypt(result, keyA, aadB)
        }
        refused("crypto reencrypt: result with the old aad") {
            CryptoServiceImpl().decrypt(result, keyB, aadA)
        }
    }

    // --- (2) the same bytes --------------------------------------------------------------------

    @Test
    fun `plaintext that is not UTF-8 survives byte for byte`() {
        val odd = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte(), 0xC3.toByte())
        val input = encryptIndependently(odd, keyA.bytes, aadA, nonceTwo)

        val result = service(CountingNonces(nonceOne)).reencrypt(input, keyA, aadA, keyB, aadB)

        // A path through a String would replace these bytes, and the arrays would differ.
        val opened = openIndependently(result, keyBBytes, aadB)
        assertArrayEquals("crypto reencrypt: non UTF-8 bytes", odd, opened)
    }

    // --- (3) the refusals ----------------------------------------------------------------------

    @Test
    fun `a wrong old key is refused by the tag check and no nonce is asked`() {
        val wrongKey = DataEncryptionKey(patterned(0x11, 32))

        assertRefusedWithNoNonce("wrong old key", original(), oldKey = wrongKey)
    }

    @Test
    fun `a wrong old aad is refused by the tag check and no nonce is asked`() {
        // Only the old associated data differs, so the tag check is the one guard this reaches.
        assertRefusedWithNoNonce("wrong old aad", original(), oldAad = aadFor(clientB, 1))
    }

    @Test
    fun `a flipped bit in the ciphertext, the tag or the nonce is refused and no nonce is asked`() {
        val input = original()

        val flippedCiphertext = CipherText(flipBit(input.ciphertext, 0), input.nonce, input.tag)
        val flippedTag = CipherText(input.ciphertext, input.nonce, flipBit(input.tag, 0))
        val flippedNonce = CipherText(input.ciphertext, flipBit(input.nonce, 0), input.tag)

        assertRefusedWithNoNonce("flipped ciphertext bit", flippedCiphertext)
        assertRefusedWithNoNonce("flipped tag bit", flippedTag)
        assertRefusedWithNoNonce("flipped nonce bit", flippedNonce)
    }

    @Test
    fun `an empty ciphertext with the tag of another message is refused and no nonce is asked`() {
        // The tag check is the guard: the tag belongs to a message that is not empty.
        val input = original()
        val empty = CipherText(ByteArray(0), input.nonce, input.tag)

        assertRefusedWithNoNonce("empty ciphertext with a foreign tag", empty)
    }

    @Test
    fun `a tag of 15 bytes and a nonce of 11 bytes are refused by the size guards`() {
        val input = original()

        val shortTag = CipherText(input.ciphertext, input.nonce, input.tag.copyOf(15))
        val shortNonce = CipherText(input.ciphertext, input.nonce.copyOf(11), input.tag)

        assertRefusedWithNoNonce("tag of 15 bytes", shortTag)
        assertRefusedWithNoNonce("nonce of 11 bytes", shortNonce)
    }

    @Test
    fun `a key of 31 bytes is refused by the size guard`() {
        val shortKey = DataEncryptionKey(ByteArray(31) { 1 })

        // Each of these is a size guard that runs before any cipher; the other key is a good one.
        assertRefusedWithNoNonce("old key of 31 bytes", original(), oldKey = shortKey)
        assertRefusedWithNoNonce("new key of 31 bytes", original(), newKey = shortKey)
    }

    @Test
    fun `a ciphertext of 524289 bytes is refused by the cap before the cipher`() {
        val tag = original().tag

        // Zero filled, so the cipher could never open it: the nonce source is untouched.
        val zeroFilled = CipherText(ByteArray(524289), nonceTwo, tag)
        assertRefusedWithNoNonce("zero filled ciphertext of 524289 bytes", zeroFilled)

        // A genuine ciphertext one byte over the cap opens fine for the platform cipher, so the
        // cap is the guard that refuses it.
        val oversize = encryptIndependently(ByteArray(524289) { 7 }, keyA.bytes, aadA, nonceTwo)
        assertEquals("crypto reencrypt: oversize ciphertext length", 524289, oversize.ciphertext.size)
        assertRefusedWithNoNonce("genuine ciphertext of 524289 bytes", oversize)
    }

    // --- (4) the cap boundary ------------------------------------------------------------------

    @Test
    fun `a ciphertext of exactly 524288 bytes is accepted`() {
        val big = ByteArray(524288) { (it % 251).toByte() }
        val input = encryptIndependently(big, keyA.bytes, aadA, nonceTwo)
        assertEquals("crypto reencrypt: boundary ciphertext length", 524288, input.ciphertext.size)

        val result = service(CountingNonces(nonceOne)).reencrypt(input, keyA, aadA, keyB, aadB)

        assertArrayEquals("crypto reencrypt: boundary bytes", big, openIndependently(result, keyBBytes, aadB))
    }

    // --- (5) the nonce source ------------------------------------------------------------------

    @Test
    fun `a nonce of 11 bytes from the source is refused after the open`() {
        val nonces = ShortNonces()

        assertPlainRefusal("short nonce from the source") {
            service(nonces).reencrypt(original(), keyA, aadA, keyB, aadB)
        }
        assertEquals("crypto reencrypt: the source is asked once, after the open", 1, nonces.calls)
    }

    // --- (6) no result, input unchanged, (7) keys unchanged -------------------------------------

    @Test
    fun `a refused call returns nothing and leaves the input, the keys and the aad unchanged`() {
        val input = original()
        val before = CipherText(input.ciphertext.copyOf(), input.nonce.copyOf(), input.tag.copyOf())
        val oldBefore = keyA.bytes.copyOf()
        val newBefore = keyB.bytes.copyOf()
        val oldAadBefore = aadA.copyOf()
        val newAadBefore = aadB.copyOf()
        var result: CipherText? = null

        assertThrows("crypto reencrypt: a refusal must throw", CryptoRefused::class.java) {
            result = service(CountingNonces(nonceOne))
                .reencrypt(input, DataEncryptionKey(patterned(0x11, 32)), aadA, keyB, aadB)
        }

        assertNull("crypto reencrypt: a refusal must leave no result", result)
        assertEquals("crypto reencrypt: refused input unchanged", before, input)
        assertArrayEquals("crypto reencrypt: old key unchanged", oldBefore, keyA.bytes)
        assertArrayEquals("crypto reencrypt: new key unchanged", newBefore, keyB.bytes)
        assertArrayEquals("crypto reencrypt: old aad unchanged", oldAadBefore, aadA)
        assertArrayEquals("crypto reencrypt: new aad unchanged", newAadBefore, aadB)
    }

    @Test
    fun `a successful call leaves the input, the keys and the aad unchanged`() {
        val input = original()
        val before = CipherText(input.ciphertext.copyOf(), input.nonce.copyOf(), input.tag.copyOf())
        val oldBefore = keyA.bytes.copyOf()
        val newBefore = keyB.bytes.copyOf()
        val oldAadBefore = aadA.copyOf()
        val newAadBefore = aadB.copyOf()

        service(CountingNonces(nonceOne)).reencrypt(input, keyA, aadA, keyB, aadB)

        assertEquals("crypto reencrypt: input unchanged after success", before, input)
        assertArrayEquals("crypto reencrypt: old key unchanged after success", oldBefore, keyA.bytes)
        assertArrayEquals("crypto reencrypt: new key unchanged after success", newBefore, keyB.bytes)
        assertArrayEquals("crypto reencrypt: old aad unchanged after success", oldAadBefore, aadA)
        assertArrayEquals("crypto reencrypt: new aad unchanged after success", newAadBefore, aadB)
    }

    // --- (8) the constant ----------------------------------------------------------------------

    @Test
    fun `the plaintext cap stays the literal number`() {
        assertEquals("crypto reencrypt: plaintext cap", 524288, UNSEAL_PLAINTEXT_MAX_BYTES)
    }
}
