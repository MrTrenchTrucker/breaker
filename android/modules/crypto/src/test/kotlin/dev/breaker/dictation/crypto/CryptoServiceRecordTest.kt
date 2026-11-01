package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Known-answer tests for the AES-GCM records of [CryptoServiceImpl]: [CryptoServiceImpl.encrypt]
 * and [CryptoServiceImpl.decrypt]. The vectors and helpers are in [CryptoServiceTestFixture].
 */
class CryptoServiceRecordTest : CryptoServiceTestFixture() {
    // ---- the oracle and the platform cipher agree -------------------------------------------

    @Test
    fun `the platform cipher reproduces the published GCM test case sixteen`() {
        val sealed = jcaSeal(hex(caseKey), hex(caseIv), hex(caseAad), hex(casePlaintext))

        assertArrayEquals(
            "crypto: AES-GCM test case 16 ciphertext and tag are not the published bytes",
            hex(caseCiphertext) + hex(caseTag),
            sealed,
        )
    }

    // ---- encrypt -----------------------------------------------------------------------------

    @Test
    fun `encrypt gives the independent ciphertext nonce and tag for key version one`() {
        val service = serviceWith(CountingNonces(nonceOne))

        val result = service.encrypt(r1Text, dek, aadFor(clientA, 1))

        assertArrayEquals("crypto: ciphertext part differs from the independent vector", hex(v1Ciphertext), result.ciphertext)
        assertArrayEquals("crypto: nonce part is not the nonce the source gave", nonceOne, result.nonce)
        assertArrayEquals("crypto: tag part differs from the independent vector", hex(v1Tag), result.tag)
    }

    @Test
    fun `encrypt joined as nonce then ciphertext then tag equals the independent joined bytes`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val result = service.encrypt(r1Text, dek, aadFor(clientA, 1))

        val joined = result.nonce + result.ciphertext + result.tag

        assertArrayEquals(
            "crypto: the joined nonce, ciphertext and tag are not the independent single-string bytes",
            Base64.getDecoder().decode(v1NonceBase64 + v1CiphertextBase64 + v1TagBase64),
            joined,
        )
    }

    @Test
    fun `encrypt for key version two gives the same ciphertext and a different tag`() {
        val service = serviceWith(CountingNonces(nonceOne))

        val result = service.encrypt(r1Text, dek, aadFor(clientA, 2))

        assertArrayEquals("crypto: ciphertext for key version 2 changed", hex(v1Ciphertext), result.ciphertext)
        assertArrayEquals("crypto: the tag does not depend on the key version in the associated data", hex(v2Tag), result.tag)
    }

    @Test
    fun `encrypt of the empty text gives no ciphertext bytes and the independent tag`() {
        val service = serviceWith(CountingNonces(nonceTwo))

        val result = service.encrypt("", dek, aadFor(clientB, 1))

        assertEquals("crypto: an empty text must give no ciphertext bytes", 0, result.ciphertext.size)
        assertArrayEquals("crypto: tag of the empty text differs from the independent vector", hex(v3Tag), result.tag)
        assertArrayEquals("crypto: nonce of the empty text is not the one the source gave", nonceTwo, result.nonce)
    }

    @Test
    fun `encrypt refuses a key that is not 32 bytes and does not ask for a nonce`() {
        val nonces = CountingNonces(nonceOne)
        val service = serviceWith(nonces)
        val shortKey = DataEncryptionKey(ByteArray(31) { 1 })

        refused("crypto: encrypt accepted a 31-byte key") { service.encrypt(r1Text, shortKey, aadFor(clientA, 1)) }

        assertEquals("crypto: a refused encrypt must not consume a nonce", 0, nonces.calls)
    }

    @Test
    fun `encrypt refuses a key of 16 bytes that the cipher would accept`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val aes128Key = DataEncryptionKey(patterned(0xA0, 16))

        refused("crypto: encrypt accepted a 16-byte key") { service.encrypt(r1Text, aes128Key, aadFor(clientA, 1)) }
    }

    @Test
    fun `encrypt refuses a nonce that is not 12 bytes`() {
        val service = serviceWith(CountingNonces(nonceOne.copyOf(11)))

        refused("crypto: encrypt accepted an 11-byte nonce from the source") {
            service.encrypt(r1Text, dek, aadFor(clientA, 1))
        }
    }

    // ---- decrypt -----------------------------------------------------------------------------

    @Test
    fun `decrypt opens the independent vectors`() {
        val service = serviceWith(CountingNonces(nonceOne))

        assertEquals(
            "crypto: vector one did not decrypt to the text",
            r1Text,
            service.decrypt(vectorOne(), dek, aadFor(clientA, 1)),
        )
        assertEquals(
            "crypto: vector two did not decrypt to the text",
            r1Text,
            service.decrypt(CipherText(hex(v1Ciphertext), nonceOne.copyOf(), hex(v2Tag)), dek, aadFor(clientA, 2)),
        )
        assertEquals(
            "crypto: vector three did not decrypt to the empty text",
            "",
            service.decrypt(CipherText(ByteArray(0), nonceTwo.copyOf(), hex(v3Tag)), dek, aadFor(clientB, 1)),
        )
    }

    @Test
    fun `decrypt refuses a wrong client id in the associated data`() {
        val service = serviceWith(CountingNonces(nonceOne))

        refused("crypto: decrypt accepted another client id") { service.decrypt(vectorOne(), dek, aadFor(clientB, 1)) }
    }

    @Test
    fun `decrypt refuses a wrong key version in the associated data`() {
        val service = serviceWith(CountingNonces(nonceOne))

        refused("crypto: decrypt accepted another key version") { service.decrypt(vectorOne(), dek, aadFor(clientA, 2)) }
    }

    @Test
    fun `decrypt refuses empty associated data for a record sealed with data`() {
        val service = serviceWith(CountingNonces(nonceOne))

        refused("crypto: decrypt accepted empty associated data") { service.decrypt(vectorOne(), dek, ByteArray(0)) }
    }

    @Test
    fun `decrypt refuses one flipped tag bit`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val damaged = CipherText(hex(v1Ciphertext), nonceOne.copyOf(), flipOneBit(hex(v1Tag)))

        refused("crypto: decrypt accepted a record with one flipped tag bit") {
            service.decrypt(damaged, dek, aadFor(clientA, 1))
        }
    }

    @Test
    fun `decrypt refuses one flipped ciphertext bit`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val damaged = CipherText(flipOneBit(hex(v1Ciphertext)), nonceOne.copyOf(), hex(v1Tag))

        refused("crypto: decrypt accepted a record with one flipped ciphertext bit") {
            service.decrypt(damaged, dek, aadFor(clientA, 1))
        }
    }

    @Test
    fun `decrypt refuses a nonce of 11 bytes with a tag of 16`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val damaged = CipherText(hex(v1Ciphertext), nonceOne.copyOf(11), hex(v1Tag))

        refused("crypto: decrypt accepted an 11-byte nonce") { service.decrypt(damaged, dek, aadFor(clientA, 1)) }
    }

    @Test
    fun `decrypt refuses a record that is genuine under an 11-byte nonce`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val record = CipherText(hex(nonceElevenCiphertext), patterned(0x90, 11), hex(nonceElevenTag))

        refused("crypto: decrypt opened a record sealed under an 11-byte nonce") {
            service.decrypt(record, dek, aadFor(clientA, 1))
        }
    }

    @Test
    fun `decrypt refuses a nonce of 12 bytes with a tag of 15`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val damaged = CipherText(hex(v1Ciphertext), nonceOne.copyOf(), hex(v1Tag).copyOf(15))

        refused("crypto: decrypt accepted a 15-byte tag (27 bytes of framing)") {
            service.decrypt(damaged, dek, aadFor(clientA, 1))
        }
    }

    @Test
    fun `decrypt refuses a tag of 15 bytes whose missing byte was moved to the end of the ciphertext`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val tag = hex(v1Tag)
        // Joined as ciphertext then tag, these bytes are exactly the genuine record, so the cipher
        // alone would open them; only the rule that a tag is 16 bytes refuses them.
        val shifted = CipherText(hex(v1Ciphertext) + tag.copyOf(1), nonceOne.copyOf(), tag.copyOfRange(1, 16))

        refused("crypto: decrypt accepted a 15-byte tag") { service.decrypt(shifted, dek, aadFor(clientA, 1)) }
    }

    @Test
    fun `decrypt refuses a key of 31 bytes and a different key of 32 bytes`() {
        val service = serviceWith(CountingNonces(nonceOne))

        refused("crypto: decrypt accepted a 31-byte key") {
            service.decrypt(vectorOne(), DataEncryptionKey(ByteArray(31) { 1 }), aadFor(clientA, 1))
        }
        refused("crypto: decrypt accepted another 32-byte key") {
            service.decrypt(vectorOne(), DataEncryptionKey(patterned(0xB0, 32)), aadFor(clientA, 1))
        }
    }

    @Test
    fun `decrypt refuses a record that is genuine under a 16-byte key`() {
        val service = serviceWith(CountingNonces(nonceOne))
        val record = CipherText(hex(aes128Ciphertext), nonceOne.copyOf(), hex(aes128Tag))
        val aes128Key = DataEncryptionKey(patterned(0xA0, 16))

        refused("crypto: decrypt opened a record sealed under a 16-byte key") {
            service.decrypt(record, aes128Key, aadFor(clientA, 1))
        }
    }

    @Test
    fun `a refusal carries no cause`() {
        val service = serviceWith(CountingNonces(nonceOne))

        val refusal = assertThrows(CryptoRefused::class.java) {
            service.decrypt(vectorOne(), dek, aadFor(clientB, 1))
        }

        assertNull("crypto: a refusal must not carry the library error as its cause", refusal.cause)
    }

    @Test
    fun `an empty text and a text with non ascii letters make a round trip`() {
        val service = CryptoServiceImpl()
        val aad = aadFor(clientA, 1)
        // Two code points above 127, written as numbers so that this file stays plain ASCII.
        val accented = "caf${233.toChar()} ${8364.toChar()} and more"

        assertEquals("crypto: the empty text did not round trip", "", service.decrypt(service.encrypt("", dek, aad), dek, aad))
        assertEquals(
            "crypto: a text with non ascii letters did not round trip",
            accented,
            service.decrypt(service.encrypt(accented, dek, aad), dek, aad),
        )
    }
}
