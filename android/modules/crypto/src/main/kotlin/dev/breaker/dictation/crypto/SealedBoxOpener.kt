package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused
import java.security.MessageDigest
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/** The length of the ephemeral public key at the front of a sealed box, in bytes. */
private const val SEALED_EPK_BYTES = 32

/** The length of the Poly1305 tag that follows it, in bytes. */
private const val SEALED_TAG_BYTES = 16

/** The shortest legal box: the ephemeral key and the tag, an empty message. */
private const val SEALED_MIN_BOX_BYTES = SEALED_EPK_BYTES + SEALED_TAG_BYTES

/** The length of a recipient private key, in bytes (an X25519 key). */
private const val SEALED_PRIVATE_KEY_BYTES = 32

/** The nonce is the 24-byte BLAKE2b digest of the two public keys, asked for in bits. */
private const val SEALED_NONCE_BITS = 192

/** The length of the nonce, in bytes. */
private const val SEALED_NONCE_BYTES = 24

/** The first keystream bytes belong to the Poly1305 key, so the message starts after them. */
private const val SEALED_POLY_KEY_BYTES = 32

/** The length of the all-zero input that HSalsa20 is given, in bytes. */
private const val SEALED_HSALSA_INPUT_BYTES = 16

/**
 * Turns the subkey and nonce of one sealed box into the message, once the tag has been
 * accepted. It is a seam of its own so a test can count how often it is called: a box whose
 * tag is wrong must never reach it.
 *
 * [body] is the ciphertext without the tag. The result is the message, the same length.
 */
internal fun interface BodyCipher {
    fun apply(subkey: ByteArray, nonce24: ByteArray, body: ByteArray): ByteArray
}

/** The real [BodyCipher]: the XSalsa20 keystream from byte 32 onward, xored with the body. */
internal object DefaultBodyCipher : BodyCipher {
    override fun apply(subkey: ByteArray, nonce24: ByteArray, body: ByteArray): ByteArray {
        val message = ByteArray(body.size)
        // The first 32 keystream bytes are the Poly1305 key of this box. They are not needed
        // here, but they are key material, so both arrays are zeroed on every path.
        val skipped = ByteArray(SEALED_POLY_KEY_BYTES)
        val keystreamHead = ByteArray(SEALED_POLY_KEY_BYTES)
        try {
            val engine = sealedEngine(subkey, nonce24)
            engine.processBytes(skipped, 0, skipped.size, keystreamHead, 0)
            if (body.isNotEmpty()) engine.processBytes(body, 0, body.size, message, 0)
            return message
        } catch (e: Exception) {
            message.fill(0) // throw path: no partial message leaves
            throw e
        } finally {
            skipped.fill(0)
            keystreamHead.fill(0)
        }
    }
}

/** A fresh XSalsa20 engine keyed with [subkey] and started on [nonce24]. */
private fun sealedEngine(subkey: ByteArray, nonce24: ByteArray): XSalsa20Engine {
    val engine = XSalsa20Engine()
    engine.init(false, ParametersWithIV(KeyParameter(subkey), nonce24))
    return engine
}

/** The nonce of a sealed box: BLAKE2b with a 24-byte output over the two public keys, in that order. */
private fun sealedNonce(epk: ByteArray, rpk: ByteArray): ByteArray {
    val digest = Blake2bDigest(SEALED_NONCE_BITS)
    digest.update(epk, 0, epk.size)
    digest.update(rpk, 0, rpk.size)
    val nonce = ByteArray(SEALED_NONCE_BYTES)
    digest.doFinal(nonce, 0)
    return nonce
}

/** The Poly1305 key: the first 32 bytes of the keystream. The caller zeroes the result. */
private fun sealedPolyKey(subkey: ByteArray, nonce24: ByteArray): ByteArray {
    val engine = sealedEngine(subkey, nonce24)
    val key = ByteArray(SEALED_POLY_KEY_BYTES)
    val zeros = ByteArray(SEALED_POLY_KEY_BYTES)
    try {
        engine.processBytes(zeros, 0, zeros.size, key, 0)
        return key
    } catch (e: Exception) {
        key.fill(0) // throw path: the caller never gets this array, so it is zeroed here
        throw e
    }
}

/**
 * The Poly1305 tag of [data] under [polyKey]. The key is cleared the standard way (the top
 * four bits of bytes 3, 7, 11 and 15, the low two bits of bytes 4, 8 and 12) on a copy
 * first, so the result does not depend on whether the library clears it itself.
 */
private fun sealedTag(polyKey: ByteArray, data: ByteArray): ByteArray {
    val clamped = polyKey.copyOf()
    try {
        for (i in intArrayOf(3, 7, 11, 15)) clamped[i] = (clamped[i].toInt() and 0x0F).toByte()
        for (i in intArrayOf(4, 8, 12)) clamped[i] = (clamped[i].toInt() and 0xFC).toByte()
        val mac = Poly1305()
        mac.init(KeyParameter(clamped))
        if (data.isNotEmpty()) mac.update(data, 0, data.size)
        val tag = ByteArray(SEALED_TAG_BYTES)
        mac.doFinal(tag, 0)
        return tag
    } finally {
        clamped.fill(0)
    }
}

/**
 * True when every byte of [bytes] is zero. Looks at every byte, whatever the content. It is
 * internal only so a test can reach it: the library may already throw on a zero shared
 * secret, and then no box can show whether this check is wired in.
 */
internal fun sealedAllZero(bytes: ByteArray): Boolean {
    var seen = 0
    for (b in bytes) seen = seen or b.toInt()
    return seen == 0
}

/**
 * Opens a libsodium `crypto_box_seal` box with the recipient's private key.
 *
 * A box is the sender's ephemeral public key (32 bytes), then the Poly1305 tag (16 bytes),
 * then the ciphertext. The key is the HSalsa20 of the X25519 shared secret, the nonce is the
 * BLAKE2b digest of the ephemeral and the recipient public key, and the cipher is XSalsa20.
 * The recipient public key is worked out from the private key here, so a caller cannot hand
 * in a public key that does not match.
 *
 * The tag is checked, in constant time, before any plaintext byte is made. Every failure,
 * including any exception from the library, is the one plain [CryptoRefused], with no
 * partial output and nothing of the box or the key in the message. A box has no sender
 * authentication: anyone who knows the recipient public key can make one.
 *
 * [bodyCipher] is only replaced in tests.
 */
internal class SealedBoxOpener(private val bodyCipher: BodyCipher = DefaultBodyCipher) {
    /**
     * The message inside [box], opened with [recipientPrivateKey] (exactly 32 bytes).
     *
     * Throws [CryptoRefused] for a box shorter than 48 bytes, a key that is not 32 bytes, a
     * low-order ephemeral key (an all-zero shared secret), a wrong tag and any library error.
     */
    fun open(box: ByteArray, recipientPrivateKey: ByteArray): ByteArray {
        if (box.size < SEALED_MIN_BOX_BYTES) throw CryptoRefused()
        if (recipientPrivateKey.size != SEALED_PRIVATE_KEY_BYTES) throw CryptoRefused()
        var shared = ByteArray(0)
        var subkey = ByteArray(0)
        var polyKey = ByteArray(0)
        try {
            val epk = box.copyOfRange(0, SEALED_EPK_BYTES)
            val tag = box.copyOfRange(SEALED_EPK_BYTES, SEALED_MIN_BOX_BYTES)
            val ciphertext = box.copyOfRange(SEALED_MIN_BOX_BYTES, box.size)

            val privateKey = X25519PrivateKeyParameters(recipientPrivateKey, 0)
            val rpk = privateKey.generatePublicKey().encoded
            val agreement = X25519Agreement()
            agreement.init(privateKey)
            shared = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
            if (sealedAllZero(shared)) throw CryptoRefused()

            subkey = hsalsa20(shared, ByteArray(SEALED_HSALSA_INPUT_BYTES))
            val nonce = sealedNonce(epk, rpk)
            polyKey = sealedPolyKey(subkey, nonce)
            val expectedTag = sealedTag(polyKey, ciphertext)
            if (!MessageDigest.isEqual(expectedTag, tag)) throw CryptoRefused()

            return bodyCipher.apply(subkey, nonce, ciphertext)
        } catch (refused: CryptoRefused) {
            throw refused
        } catch (e: Exception) {
            throw CryptoRefused()
        } finally {
            shared.fill(0)
            subkey.fill(0)
            polyKey.fill(0)
        }
    }
}
