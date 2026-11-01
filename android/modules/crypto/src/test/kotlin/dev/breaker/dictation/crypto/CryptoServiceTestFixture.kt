package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.WrappedDek
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertThrows

/**
 * The vectors, keys and helpers shared by the two test classes of [CryptoServiceImpl]:
 * [CryptoServiceRecordTest] (encrypt and decrypt) and [CryptoServiceWrappedKeyNonceAndTextFormTest]
 * (the wrapped data key, the nonces and the transportable form). It holds no test itself.
 *
 * Every expected value is an independent oracle, not recomputed here from the code under test:
 * the AES-GCM bytes come from the python `cryptography` library, and the platform cipher is
 * first checked against the published GCM test case 16 so that the oracle and the platform are
 * known to agree. The keys and nonces are counting patterns, built by [patterned], so no key
 * is written out as digits.
 *
 * The associated data in these vectors is the UTF-8 client id, a newline, and the decimal key
 * version. The service treats it as opaque bytes; the vectors are what pin that form.
 */
abstract class CryptoServiceTestFixture {
    internal val dekBytes = patterned(0xA0, 32)
    internal val dek = DataEncryptionKey(dekBytes)
    internal val nonceOne = patterned(0x90, 12)
    internal val nonceTwo = patterned(0x01, 12)
    internal val kekBytes = patterned(0x60, 32)
    internal val clientA = "3f2a9c1e-7b4d-4e86-a1c5-9d0e2b7f6a48"
    internal val clientB = "c6a1e4b0-52d9-4f37-8b1a-e07c93d5a2f6"

    internal val r1Text =
        "{\"v\":1,\"text\":\"hello from the truck\",\"source\":\"local\",\"model\":\"small\"," +
            "\"duration_ms\":1500,\"created_at\":\"2026-11-01T12:00:00.000Z\"}"

    // GCM test case 16 of the published GCM specification (AES-256, 12-byte IV, 60 bytes of
    // plaintext, 20 bytes of associated data).
    internal val caseKey =
        "feffe9928665" +
            "731c6d6a8f9467308" +
            "308feffe9928665" +
            "731c6d6a8f9467308" +
            "308"
    internal val caseIv = "cafebabefacedbaddecaf888"
    internal val casePlaintext =
        "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a31" +
            "8a721c3c0c9595680" +
            "9532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
    internal val caseAad = "feedfacedeadbeeffeedfacedeadbeef" + "abaddad2"
    internal val caseCiphertext =
        "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555" +
            "d1aa8cb08e48590dbb3da7b08b1056828" +
            "838c5f61e6393ba7a0abcc9f662"
    internal val caseTag = "76fc6ece0f4e1768cddf8853bb2d551b"

    // Framing vector one: client A, key version 1, the JSON-like text of a record body, nonce one.
    internal val v1Ciphertext =
        "62dddae8e78bdc2ff29db2713f46091ff299" +
            "929007f957eec21d07fbd21f7d1cc25ec45e37063ff6f1c8d1248266" +
            "9275c4b1c9252553c7b0d3c53d2a0a51ee0be028908ac8cb1e1e9f906e23" +
            "4494f8ff65eb870462fca599d8b728f6f185d55a79d809ce3bfd358f1a12" +
            "5338db4ba867eeae0342e8943c4ea420e11a15b9d2ccc8"
    internal val v1Tag = "94a49b106e88ba40e57cb1c51e83cb0a"
    internal val v1NonceBase64 = "kJGSk5SVlpeYmZqb"
    internal val v1CiphertextBase64 =
        "Yt3a6OeL3C/ynbJxP0YJH/KZkpAH+Vfuwh0H+9IffRzCXsReNwY/9vHI0SSC" +
            "ZpJ1xLHJJSVTx7DTxT0qClHuC+AokIrIyx4en5BuI0SU+P9l64cEYvylmdi3" +
            "KPbxhdVaedgJzjv9NY8aElM420uoZ+6uA0LolDxOpCDhGhW50szI"
    internal val v1TagBase64 = "lKSbEG6IukDlfLHFHoPLCg=="

    // Framing vector two: the same text and nonce, key version 2. The ciphertext is the same as
    // in vector one (counter mode does not depend on the associated data); only the tag differs.
    internal val v2Tag = "b6dcf07f2c374f38fb5b01bf37d0baa8"

    // Framing vector three: client B, key version 1, the empty text, nonce two.
    internal val v3Tag = "046fff7749f08278dd435f6378a2deec"
    internal val v3NonceBase64 = "AQIDBAUGBwgJCgsM"
    internal val v3TagBase64 = "BG//d0nwgnjdQ19jeKLe7A=="

    // Wrapped data key: nonce pattern 0x50, the key pattern 0xA0 sealed under the key pattern 0x60,
    // empty associated data. 60 bytes.
    internal val wrappedHex =
        "5051525" +
            "3545556" +
            "5758595a5b1670836a2327286" +
            "1670f4b8880fea5ddf8df78bf762a7dd9d720276bff5548c816a0ea33ec2" +
            "e3894c4892528" +
            "8484c5d6"

    // The same wrapping, but around the first 31 and the first 33 bytes of the pattern 0xA0 (59 and
    // 61 bytes). Both are genuine AES-GCM seals under the same key, nonce and empty associated data,
    // so the cipher opens them: only the rule that a wrapped key is exactly 60 bytes refuses them.
    internal val wrapped31Hex =
        "5051525" +
            "3545556" +
            "5758595a5b1670836a2327286" +
            "1670f4b8880fea5ddf8df78bf762a7dd9d720276bff5548413" +
            "7ef28d117f9fc135fe30769e4db6d"
    internal val wrapped33Hex =
        "5051525" +
            "3545556" +
            "5758595a5b1670836a2327286" +
            "1670f4b8880fea5ddf8df78bf762a7dd9d720276bff5548c8a2cae96" +
            "0cbd14b41f4968766" +
            "20d8a569d8"

    // Two records that the cipher opens but the service must refuse: one sealed under an 11-byte
    // nonce (the pattern 0x90, 11 bytes), one under a 16-byte key (the pattern 0xA0, 16 bytes).
    // Both carry the vector-one text, client A and key version 1.
    internal val nonceElevenCiphertext =
        "02a0c7eb48bd8eae46d1995fdccb50929a70fa75a7b3d1ed6efa9b09" +
            "c33c2423f13b7996182e5d0bd343f8488688a67f7580144" +
            "070c412628b056e8b552e85826bee8439f75b0e8388fb82e6a043356" +
            "46efec9018183" +
            "2e05a881d5ad4d22a9a6d3d5532767" +
            "5ca5d4a99f209ec948c10c48f590563f3b4e029cbbcf314b258550d1"
    internal val nonceElevenTag = "481a1e21e0c1026e584831f422555ab4"
    internal val aes128Ciphertext =
        "aadc7193de771f9d6b7751c8c8ae709cfaf59237b91b2ee3065d9773" +
            "541cd6a530acebc506e61bd9317368" +
            "5217817" +
            "82c26c8067d82d066322fda5db1e0c7000528" +
            "992780bf7b3aad925c31574a7b18796a6e3587140d8902bbb28d9106" +
            "41e8986b14f524eebc35ce3ed6d65c6e35aef289b2b3b8e6994011" +
            "8b89688d239f6418f1"
    internal val aes128Tag = "836477da2f1480cf525acc7725810" + "574"

    internal fun serviceWith(nonces: NonceSource) = CryptoServiceImpl(nonces, SealedBoxOpener())

    internal fun aadFor(clientId: String, keyVersion: Int): ByteArray =
        "$clientId\n$keyVersion".toByteArray(Charsets.UTF_8)

    internal fun vectorOne() = CipherText(hex(v1Ciphertext), nonceOne.copyOf(), hex(v1Tag))

    internal fun flipOneBit(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        copy[0] = (copy[0].toInt() xor 1).toByte()
        return copy
    }

    /** The platform cipher used directly, only to check the oracle and to build wrapped keys. */
    internal fun jcaSeal(key: ByteArray, iv: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    internal fun wrapUnder(key: ByteArray, plain: ByteArray): ByteArray =
        patterned(0x50, 12) + jcaSeal(key, patterned(0x50, 12), ByteArray(0), plain)

    internal fun wrapped(bytes: ByteArray) = WrappedDek(bytes, ByteArray(16) { 7 })

    internal fun refused(message: String, block: () -> Unit) {
        assertThrows(message, CryptoRefused::class.java) { block() }
    }

    internal fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex text must have an even length" }
        return ByteArray(text.length / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    /** [size] bytes counting up from [base], wrapping at 256. */
    internal fun patterned(base: Int, size: Int): ByteArray = ByteArray(size) { i -> (base + i).toByte() }
}

/** A nonce source that always returns the same bytes and counts how often it was asked. */
internal class CountingNonces(private val fixed: ByteArray) : NonceSource {
    var calls = 0

    override fun next(): ByteArray {
        calls++
        return fixed.copyOf()
    }
}

/** A nonce source whose call number n returns the 12 bytes counting up from base plus n. */
internal class CountingUpNonces(private val base: Int) : NonceSource {
    var calls = 0

    override fun next(): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES) { i -> (base + calls + i).toByte() }
        calls++
        return nonce
    }
}
