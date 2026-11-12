package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves [AccountKeyServiceImpl.encryptBytes] and [AccountKeyServiceImpl.decryptBytes] with the real
 * implementation and real AES-GCM: the bytes survive untouched (also bytes that are not UTF-8), the
 * result is the same as the String form for the same text and nonce, every refusal is the one plain
 * [CryptoRefused], and the nonce source is asked once on an encrypt success and never otherwise.
 *
 * The ciphertexts that the decrypt tests open are made with the platform cipher directly (the
 * fixture's `jcaSeal`), so they do not depend on the code under test. The zeroing of the internal
 * copies is not observable from a test, so `test_bytes_limits.py` guards the `fill(0)` as text.
 */
class AccountKeyBytesTest : CryptoServiceTestFixture() {
    private val aad = aadFor(clientA, 1)
    private val otherAad = aadFor(clientB, 1)
    private val otherKey = DataEncryptionKey(patterned(0x11, 32))
    private val shortKey = DataEncryptionKey(ByteArray(31) { 1 })
    private val text = "hello from the truck"
    private val textBytes = text.toByteArray(Charsets.UTF_8)

    // The first three bytes can never start a valid UTF-8 text; a String path would replace them.
    private val oddBytes =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte()) +
            ByteArray(2000) { i -> (0x80 + i * 7).toByte() }

    /** The service under test, with the given nonce source and an entropy source it never uses. */
    private fun service(nonces: NonceSource) =
        AccountKeyServiceImpl(nonces, { size -> ByteArray(size) }, SealedBoxOpener())

    /** A genuine record made with the platform cipher: key [dek], nonce two, the associated data [aad]. */
    private fun sealIndependently(plain: ByteArray): CipherText {
        val sealed = jcaSeal(dek.bytes, nonceTwo, aad, plain)
        val tagStart = sealed.size - 16
        return CipherText(
            sealed.copyOfRange(0, tagStart),
            nonceTwo.copyOf(),
            sealed.copyOfRange(tagStart, sealed.size),
        )
    }

    private fun flipBit(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        copy[0] = (copy[0].toInt() xor 1).toByte()
        return copy
    }

    /** The plain refusal: exact class, the plain message, no cause. */
    private fun assertPlainRefusal(label: String, block: () -> Unit) {
        val thrown = assertThrows("crypto bytes: $label must be refused", Throwable::class.java) {
            block()
        }
        assertEquals(
            "crypto bytes: $label must be a plain CryptoRefused",
            CryptoRefused::class.java,
            thrown.javaClass,
        )
        assertEquals("crypto bytes: $label message", CryptoRefused().message, thrown.message)
        assertNull("crypto bytes: $label must have no cause", thrown.cause)
    }

    /** One encrypt refusal before the nonce is made: the plain refusal and zero nonce requests. */
    private fun assertEncryptRefused(label: String, plain: ByteArray, key: DataEncryptionKey = dek) {
        val nonces = CountingNonces(nonceOne)
        assertPlainRefusal(label) { service(nonces).encryptBytes(plain, key, aad) }
        assertEquals("crypto bytes: $label must not ask for a nonce", 0, nonces.calls)
    }

    /** One decrypt refusal: the plain refusal and zero nonce requests. */
    private fun assertDecryptRefused(
        label: String,
        input: CipherText,
        key: DataEncryptionKey = dek,
        withAad: ByteArray = aad,
    ) {
        val nonces = CountingNonces(nonceOne)
        assertPlainRefusal(label) { service(nonces).decryptBytes(input, key, withAad) }
        assertEquals("crypto bytes: $label must not ask for a nonce", 0, nonces.calls)
    }

    /** A nonce source that hands out 11 bytes and counts how often it was asked. */
    private class ShortNonces : NonceSource {
        var calls = 0

        override fun next(): ByteArray {
            calls++
            return ByteArray(11)
        }
    }

    // --- (a) the round trip ----------------------------------------------------------------------

    @Test
    fun `bytes that are not UTF-8 make a round trip byte for byte`() {
        val service = service(CountingNonces(nonceOne))

        val sealed = service.encryptBytes(oddBytes, dek, aad)
        val opened = service.decryptBytes(sealed, dek, aad)

        assertEquals("crypto bytes: ciphertext length", oddBytes.size, sealed.ciphertext.size)
        assertArrayEquals("crypto bytes: round trip of non UTF-8 bytes", oddBytes, opened)
        assertArrayEquals(
            "crypto bytes: the platform cipher opens the same bytes",
            oddBytes,
            jcaOpen(sealed),
        )
    }

    /** Opens [cipherText] with the platform cipher under the fixture key and [aad]. */
    private fun jcaOpen(cipherText: CipherText): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(dek.bytes, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, cipherText.nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(cipherText.ciphertext + cipherText.tag)
    }

    // --- (b) the empty plaintext -----------------------------------------------------------------

    @Test
    fun `an empty plaintext is valid and opens to an empty array`() {
        val service = service(CountingNonces(nonceOne))

        val sealed = service.encryptBytes(ByteArray(0), dek, aad)
        val opened = service.decryptBytes(sealed, dek, aad)

        assertEquals("crypto bytes: empty ciphertext", 0, sealed.ciphertext.size)
        assertEquals("crypto bytes: tag size of the empty message", 16, sealed.tag.size)
        assertArrayEquals(
            "crypto bytes: tag of the empty message is the platform cipher tag",
            jcaSeal(dek.bytes, nonceOne, aad, ByteArray(0)),
            sealed.tag,
        )
        assertEquals("crypto bytes: empty result", 0, opened.size)
    }

    // --- (c) the same as the String form ---------------------------------------------------------

    @Test
    fun `encryptBytes equals the String form byte for byte for the same text and nonce`() {
        val viaBytes = service(CountingNonces(nonceOne)).encryptBytes(textBytes, dek, aad)
        val viaText = serviceWith(CountingNonces(nonceOne)).encrypt(text, dek, aad)

        assertArrayEquals("crypto bytes: ciphertext part", viaText.ciphertext, viaBytes.ciphertext)
        assertArrayEquals("crypto bytes: nonce part", viaText.nonce, viaBytes.nonce)
        assertArrayEquals("crypto bytes: tag part", viaText.tag, viaBytes.tag)
    }

    @Test
    fun `the output of the String form opens with decryptBytes to the UTF-8 bytes`() {
        val viaText = serviceWith(CountingNonces(nonceOne)).encrypt(text, dek, aad)

        val opened = service(CountingNonces(nonceTwo)).decryptBytes(viaText, dek, aad)

        assertArrayEquals("crypto bytes: bytes of the text", textBytes, opened)
    }

    // --- (d) decrypt never asks for a nonce ------------------------------------------------------

    @Test
    fun `decryptBytes asks the nonce source zero times on a success`() {
        val nonces = CountingNonces(nonceOne)

        val opened = service(nonces).decryptBytes(sealIndependently(oddBytes), dek, aad)

        assertArrayEquals("crypto bytes: independent record opens", oddBytes, opened)
        assertEquals("crypto bytes: a decrypt success must not ask for a nonce", 0, nonces.calls)
    }

    // --- (e) the refusals ------------------------------------------------------------------------

    @Test
    fun `a wrong key and a wrong aad are refused by the tag check`() {
        val input = sealIndependently(textBytes)

        assertDecryptRefused("wrong key", input, key = otherKey)
        assertDecryptRefused("wrong aad", input, withAad = otherAad)
    }

    @Test
    fun `a flipped bit in the ciphertext, the tag or the nonce is refused`() {
        val input = sealIndependently(textBytes)

        val flippedCiphertext = CipherText(flipBit(input.ciphertext), input.nonce, input.tag)
        val flippedTag = CipherText(input.ciphertext, input.nonce, flipBit(input.tag))
        val flippedNonce = CipherText(input.ciphertext, flipBit(input.nonce), input.tag)

        assertDecryptRefused("flipped ciphertext bit", flippedCiphertext)
        assertDecryptRefused("flipped tag bit", flippedTag)
        assertDecryptRefused("flipped nonce bit", flippedNonce)
    }

    @Test
    fun `a tag of 15 bytes and a nonce of 11 bytes are refused by the size guards`() {
        val input = sealIndependently(textBytes)

        val shortTag = CipherText(input.ciphertext, input.nonce, input.tag.copyOf(15))
        val shortNonce = CipherText(input.ciphertext, input.nonce.copyOf(11), input.tag)

        assertDecryptRefused("tag of 15 bytes", shortTag)
        assertDecryptRefused("nonce of 11 bytes", shortNonce)
    }

    @Test
    fun `a key of 31 bytes is refused in both directions`() {
        assertEncryptRefused("encrypt with a key of 31 bytes", textBytes, key = shortKey)
        assertDecryptRefused("decrypt with a key of 31 bytes", sealIndependently(textBytes), key = shortKey)
    }

    @Test
    fun `a ciphertext of 524289 bytes is refused by the cap`() {
        val tag = sealIndependently(textBytes).tag

        // Zero filled, so the cipher could never open it.
        val zeroFilled = CipherText(ByteArray(524289), nonceTwo, tag)
        assertDecryptRefused("zero filled ciphertext of 524289 bytes", zeroFilled)

        // A genuine record one byte over the cap: the platform cipher opens it, only the cap refuses it.
        val oversize = sealIndependently(ByteArray(524289) { 7 })
        assertEquals("crypto bytes: oversize ciphertext length", 524289, oversize.ciphertext.size)
        assertDecryptRefused("genuine ciphertext of 524289 bytes", oversize)
    }

    @Test
    fun `a plaintext of 524289 bytes is refused by the cap before the nonce is asked`() {
        assertEncryptRefused("plaintext of 524289 bytes", ByteArray(524289) { 7 })
    }

    @Test
    fun `a nonce of 11 bytes from the source is refused after the size checks`() {
        val badKey = ShortNonces()
        assertPlainRefusal("short key with a short nonce source") {
            service(badKey).encryptBytes(textBytes, shortKey, aad)
        }
        assertEquals("crypto bytes: the key size is checked before the nonce", 0, badKey.calls)

        val oversize = ShortNonces()
        assertPlainRefusal("oversize plaintext with a short nonce source") {
            service(oversize).encryptBytes(ByteArray(524289), dek, aad)
        }
        assertEquals("crypto bytes: the cap is checked before the nonce", 0, oversize.calls)

        val sized = ShortNonces()
        assertPlainRefusal("short nonce from the source") { service(sized).encryptBytes(textBytes, dek, aad) }
        assertEquals("crypto bytes: the source is asked once, after the size checks", 1, sized.calls)
    }

    // --- (f) the cap boundary --------------------------------------------------------------------

    @Test
    fun `a plaintext of exactly 524288 bytes encrypts and decrypts back`() {
        val big = ByteArray(524288) { (it % 251).toByte() }
        val service = service(CountingNonces(nonceOne))

        val sealed = service.encryptBytes(big, dek, aad)

        assertEquals("crypto bytes: boundary ciphertext length", 524288, sealed.ciphertext.size)
        assertArrayEquals("crypto bytes: boundary round trip", big, service.decryptBytes(sealed, dek, aad))
    }

    // --- (g) the nonce ---------------------------------------------------------------------------

    @Test
    fun `the nonce source is asked exactly once and its nonce is the result nonce`() {
        val nonces = CountingNonces(nonceOne)

        val sealed = service(nonces).encryptBytes(textBytes, dek, aad)

        assertEquals("crypto bytes: one nonce request on a success", 1, nonces.calls)
        assertArrayEquals("crypto bytes: result nonce is the source nonce", nonceOne, sealed.nonce)
    }

    // --- (h) the caller's array ------------------------------------------------------------------

    @Test
    fun `the caller plain array is unchanged after encryptBytes`() {
        val plain = oddBytes.copyOf()

        service(CountingNonces(nonceOne)).encryptBytes(plain, dek, aad)
        assertArrayEquals("crypto bytes: plain unchanged after a success", oddBytes, plain)

        assertPlainRefusal("wrong key size") {
            service(CountingNonces(nonceOne)).encryptBytes(plain, shortKey, aad)
        }
        assertArrayEquals("crypto bytes: plain unchanged after a refusal", oddBytes, plain)
    }

    // --- (i) a fresh result array ----------------------------------------------------------------

    @Test
    fun `the array from decryptBytes is a fresh array the caller may change`() {
        val service = service(CountingNonces(nonceOne))
        val input = sealIndependently(oddBytes)

        val first = service.decryptBytes(input, dek, aad)
        first[0] = 0
        val second = service.decryptBytes(input, dek, aad)

        assertNotSame("crypto bytes: each result is its own array", first, second)
        assertArrayEquals("crypto bytes: a changed result does not change the next one", oddBytes, second)
    }
}
