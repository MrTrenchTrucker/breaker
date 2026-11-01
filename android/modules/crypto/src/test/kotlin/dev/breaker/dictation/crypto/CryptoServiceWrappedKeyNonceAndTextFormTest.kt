package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.WrappedDek
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Known-answer tests for the rest of [CryptoServiceImpl]: the wrapped data key
 * ([CryptoServiceImpl.unwrapDek]), the nonces that [CryptoServiceImpl.encrypt] draws, and the
 * transportable form ([CryptoServiceImpl.toEncryptedText]). The vectors and helpers are in
 * [CryptoServiceTestFixture].
 */
class CryptoServiceWrappedKeyNonceAndTextFormTest : CryptoServiceTestFixture() {
    // ---- nonces ------------------------------------------------------------------------------

    @Test
    fun `two encrypts of one text with the real nonce source differ in nonce and ciphertext`() {
        val service = CryptoServiceImpl()
        val aad = aadFor(clientA, 1)

        val first = service.encrypt(r1Text, dek, aad)
        val second = service.encrypt(r1Text, dek, aad)

        assertFalse("crypto: two encrypts used the same nonce", first.nonce.contentEquals(second.nonce))
        assertFalse("crypto: two encrypts gave the same ciphertext", first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun `the injected source is asked once for each encrypt and each result carries the nonce of its own call`() {
        val nonces = CountingUpNonces(0x30)
        val service = serviceWith(nonces)
        val aad = aadFor(clientA, 1)

        val first = service.encrypt(r1Text, dek, aad)
        assertEquals("crypto: one encrypt must ask the source once", 1, nonces.calls)
        val second = service.encrypt(r1Text, dek, aad)

        assertEquals("crypto: two encrypts must ask the source twice", 2, nonces.calls)
        assertArrayEquals("crypto: first result lost the first nonce", patterned(0x30, 12), first.nonce)
        assertArrayEquals("crypto: second result did not get a new nonce", patterned(0x31, 12), second.nonce)
    }

    // ---- wrapped data key --------------------------------------------------------------------

    @Test
    fun `the right key-encryption key opens the independent wrapped key`() {
        val service = serviceWith(CountingNonces(nonceOne))

        val opened = service.unwrapDek(wrapped(hex(wrappedHex)), KeyEncryptionKey(kekBytes))

        assertArrayEquals("crypto: the unwrapped key is not the independent key", dekBytes, opened.bytes)
    }

    @Test
    fun `unwrapping does not change the key-encryption key and ignores the salt`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val kek = KeyEncryptionKey(kekBytes)

        val first = service.unwrapDek(WrappedDek(hex(wrappedHex), ByteArray(16) { 1 }), kek)
        val second = service.unwrapDek(WrappedDek(hex(wrappedHex), ByteArray(16) { 2 }), kek)

        assertArrayEquals("crypto: the key-encryption key was changed by unwrapping", kekBytes, kek.copyBytes())
        assertArrayEquals("crypto: a different salt changed the unwrapped key", first.bytes, second.bytes)
    }

    @Test
    fun `a wrong key-encryption key refuses and returns nothing`() {
        val service = serviceWith(CountingNonces(nonceOne))
        var returned: DataEncryptionKey? = null

        refused("crypto: unwrap accepted a wrong key-encryption key") {
            returned = service.unwrapDek(wrapped(hex(wrappedHex)), KeyEncryptionKey(patterned(0x70, 32)))
        }

        assertNull("crypto: a refused unwrap must not hand back a key", returned)
    }

    @Test
    fun `a wrapped key of 59 or 61 bytes is refused`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val kek = KeyEncryptionKey(kekBytes)
        val good = hex(wrappedHex)

        refused("crypto: unwrap accepted 59 bytes") { service.unwrapDek(wrapped(good.copyOf(59)), kek) }
        refused("crypto: unwrap accepted 61 bytes") { service.unwrapDek(wrapped(good + byteArrayOf(0)), kek) }
    }

    @Test
    fun `a wrapped key with one flipped bit is refused`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val kek = KeyEncryptionKey(kekBytes)
        val damaged = hex(wrappedHex)
        damaged[40] = (damaged[40].toInt() xor 1).toByte()

        refused("crypto: unwrap accepted a damaged wrapped key") { service.unwrapDek(wrapped(damaged), kek) }
    }

    @Test
    fun `a valid wrapping of 31 or 33 key bytes built in the test is refused by the length guard alone`() {
        // Both wrappings are genuine AES-GCM seals under the key-encryption key with empty associated
        // data, so the cipher would open them. Nothing but the rule that a wrapped key is exactly
        // 60 bytes refuses them.
        val service = serviceWith(CountingNonces(nonceOne))
        val kek = KeyEncryptionKey(kekBytes)

        refused("crypto: unwrap accepted a valid wrapping of 31 key bytes") {
            service.unwrapDek(wrapped(wrapUnder(kekBytes, ByteArray(31) { 3 })), kek)
        }
        refused("crypto: unwrap accepted a valid wrapping of 33 key bytes") {
            service.unwrapDek(wrapped(wrapUnder(kekBytes, ByteArray(33) { 3 })), kek)
        }
    }

    @Test
    fun `an independent valid wrapping of 31 key bytes is refused by the length guard`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val blob = hex(wrapped31Hex)

        assertEquals("crypto: the 31-byte vector must be 59 bytes long", 59, blob.size)
        refused("crypto: unwrap accepted a valid 59-byte wrapping") {
            service.unwrapDek(wrapped(blob), KeyEncryptionKey(kekBytes))
        }
    }

    @Test
    fun `an independent valid wrapping of 33 key bytes is refused by the length guard`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val blob = hex(wrapped33Hex)

        assertEquals("crypto: the 33-byte vector must be 61 bytes long", 61, blob.size)
        refused("crypto: unwrap accepted a valid 61-byte wrapping") {
            service.unwrapDek(wrapped(blob), KeyEncryptionKey(kekBytes))
        }
    }

    @Test
    fun `the wrapped key built by the test helper is accepted when it is 32 bytes`() {
        val service = serviceWith(CountingNonces(nonceOne))

        val opened = service.unwrapDek(wrapped(wrapUnder(kekBytes, dekBytes)), KeyEncryptionKey(kekBytes))

        assertArrayEquals("crypto: a correctly wrapped 32-byte key was not opened", dekBytes, opened.bytes)
    }

    @Test
    fun `the password path does not exist`() {
        val parameterLists = CryptoServiceImpl::class.java.methods
            .filter { it.name == "unwrapDek" }
            .map { it.parameterTypes.toList() }
            .distinct()

        assertEquals(
            "crypto: unwrapDek must exist only in the form that takes the key-encryption key",
            listOf<List<Class<*>>>(listOf(WrappedDek::class.java, KeyEncryptionKey::class.java)),
            parameterLists,
        )
    }

    // ---- the transportable form --------------------------------------------------------------

    @Test
    fun `toEncryptedText gives three base64 strings that equal the independent ones`() {
        val service = serviceWith(CountingNonces(nonceOne))

        val text = service.toEncryptedText(vectorOne())

        assertEquals("crypto: ciphertext base64 differs", v1CiphertextBase64, text.ciphertext)
        assertEquals("crypto: nonce base64 differs", v1NonceBase64, text.nonce)
        assertEquals("crypto: tag base64 differs", v1TagBase64, text.tag)
        assertArrayEquals("crypto: ciphertext base64 does not decode to the part", hex(v1Ciphertext), Base64.getDecoder().decode(text.ciphertext))
        assertArrayEquals("crypto: nonce base64 does not decode to the part", nonceOne, Base64.getDecoder().decode(text.nonce))
        assertArrayEquals("crypto: tag base64 does not decode to the part", hex(v1Tag), Base64.getDecoder().decode(text.tag))
    }

    @Test
    fun `toEncryptedText of an empty ciphertext keeps the ciphertext an empty string`() {
        val service = serviceWith(CountingNonces(nonceTwo))

        val text = service.toEncryptedText(CipherText(ByteArray(0), nonceTwo.copyOf(), hex(v3Tag)))

        assertEquals("crypto: an empty ciphertext must be the empty string", "", text.ciphertext)
        assertEquals("crypto: nonce base64 differs for the empty text", v3NonceBase64, text.nonce)
        assertEquals("crypto: tag base64 differs for the empty text", v3TagBase64, text.tag)
    }
}
