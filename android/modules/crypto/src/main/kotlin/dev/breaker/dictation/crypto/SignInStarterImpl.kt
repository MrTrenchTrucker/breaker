package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.SignInAttempt
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.port.SignInStarter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/** The length of the random bytes behind the secret text and behind the private key, in bytes. */
private const val STARTER_RANDOM_BYTES = 32

/** The one refusal of this starter: an entropy source handed back an array of the wrong length. */
private const val STARTER_WRONG_SIZE_MESSAGE = "The entropy source returned the wrong number of bytes"

/** The one secure random source of the starter. */
private val starterRandom = SecureRandom()

/** A fresh array of [size] bytes from the shared secure random source. */
private fun secureEntropy(size: Int): ByteArray {
    val bytes = ByteArray(size)
    starterRandom.nextBytes(bytes)
    return bytes
}

/**
 * Makes one sign-in attempt: the one-time secret text, its challenge and the X25519 key pair.
 *
 * The entropy source is asked for exactly two arrays of 32 bytes, in this order. The first
 * array is the secret: the secret text is its base64url text without padding, so it is 43
 * characters and its last character is canonical. The challenge is the base64url of the
 * SHA-256 of the text's ASCII bytes, not of the decoded bytes. The second array is the X25519
 * private key, and the public key is the one that belongs to it.
 *
 * Every temporary array is zeroed before [begin] returns, and the attempt keeps its own copies.
 * A Kotlin String cannot be wiped, so the secret text and the challenge exist as strings only
 * while they are built. Nothing here is stored, logged or sent.
 */
class SignInStarterImpl(private val entropy: (Int) -> ByteArray = ::secureEntropy) : SignInStarter {
    override fun begin(): SignInAttempt {
        var secretRandom = ByteArray(0)
        var privateRandom = ByteArray(0)
        var secretBytes = ByteArray(0)
        try {
            secretRandom = takeEntropy()
            privateRandom = takeEntropy()
            val secretText = Base64.getUrlEncoder().withoutPadding().encodeToString(secretRandom)
            secretBytes = secretText.toByteArray(Charsets.US_ASCII)
            val challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(secretBytes))
            val publicKey = X25519PrivateKeyParameters(privateRandom, 0).generatePublicKey().encoded
            return SignInAttempt(secretBytes, SignInPrivateKey(privateRandom), publicKey, challenge)
        } finally {
            secretRandom.fill(0)
            privateRandom.fill(0)
            secretBytes.fill(0)
        }
    }

    /** One array from the entropy source. A wrong-length array is zeroed and refused. */
    private fun takeEntropy(): ByteArray {
        val bytes = entropy(STARTER_RANDOM_BYTES)
        if (bytes.size != STARTER_RANDOM_BYTES) {
            bytes.fill(0)
            throw IllegalArgumentException(STARTER_WRONG_SIZE_MESSAGE)
        }
        return bytes
    }
}
