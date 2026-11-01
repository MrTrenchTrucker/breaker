package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey

/**
 * The plaintext inside a sign-in package, and the one rule for reading it.
 *
 * The website page seals exactly [SIZE_BYTES] bytes: one version byte with the value
 * [FORMAT_VERSION], then the 32-byte data-encryption key, then the 32-byte X25519 box
 * private key. There is no key version inside, and the keys are not inspected: any 32 bytes
 * are accepted for each. A different length or a different version byte is refused with the
 * one plain [CryptoRefused], and nothing partial comes out.
 *
 * The array passed to [parse] is the working copy of the plaintext. It is zeroed before
 * [parse] returns, whether the package was accepted or refused.
 */
internal object SignInPackage {
    /** The exact length of the plaintext, in bytes. */
    const val SIZE_BYTES = 65

    /** The only value of the version byte that is accepted. */
    const val FORMAT_VERSION = 1

    private const val KEY_BYTES = 32

    /**
     * Read the two keys out of [plaintext], or throw [CryptoRefused] when its length or its
     * version byte is wrong. [plaintext] is zeroed on every path.
     */
    fun parse(plaintext: ByteArray): AccountKeys {
        try {
            if (plaintext.size != SIZE_BYTES || plaintext[0].toInt() != FORMAT_VERSION) {
                throw CryptoRefused()
            }
            return buildKeys(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Copy the two keys out of a [plaintext] that has already passed both checks.
     *
     * Both keys are exactly 32 bytes here, so building them cannot fail. The data-encryption
     * key keeps the array it is given, so that array is the key and is not zeroed. The box
     * private key makes its own copy, so the temporary one is zeroed in a `finally`.
     */
    private fun buildKeys(plaintext: ByteArray): AccountKeys {
        val dekBytes = plaintext.copyOfRange(1, 1 + KEY_BYTES)
        val boxBytes = plaintext.copyOfRange(1 + KEY_BYTES, SIZE_BYTES)
        try {
            return AccountKeys(DataEncryptionKey(dekBytes), BoxPrivateKey(boxBytes))
        } finally {
            boxBytes.fill(0)
        }
    }
}
