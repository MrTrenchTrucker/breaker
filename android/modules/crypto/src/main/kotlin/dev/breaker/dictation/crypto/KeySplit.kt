package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.AuthVerifier
import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KeyEncryptionKey

/** The HKDF label for the key-encryption key. Its ASCII bytes are the Expand info. */
internal const val KEK_LABEL = "breaker-kek-v1"

/** The HKDF label for the login verifier. Its ASCII bytes are the Expand info. */
internal const val AUTH_VERIFIER_LABEL = "breaker-auth-verifier-v1"

/**
 * Splits one Argon2id output into two independent subkeys: the key-encryption
 * key and the login verifier.
 *
 * Each one is HKDF-SHA256 (RFC 5869) of [argonOutput], run once per label,
 * the key-encryption key first. Extract uses an EMPTY salt, which means
 * HashLen zero bytes (RFC 5869 section 2.2). Expand uses the ASCII bytes of
 * the label as info, and each subkey is 32 bytes long. Because the labels
 * differ, knowing one subkey tells nothing about the other.
 *
 * There is no function that returns the key-encryption key alone. Both come
 * out together, so nothing that can compute the verifier without the password
 * can also produce the key.
 *
 * [argonOutput] is not modified. This function zeroes the two arrays it owns
 * after the key objects have copied them; zeroing [argonOutput] is up to the
 * caller.
 */
internal fun splitKeys(argonOutput: ByteArray): DerivedKeys {
    var kekBytes: ByteArray? = null
    var verifierBytes: ByteArray? = null
    try {
        kekBytes = hkdfSha256(
            ikm = argonOutput,
            salt = ByteArray(0),
            info = KEK_LABEL.toByteArray(Charsets.US_ASCII),
            length = KeyEncryptionKey.SIZE_BYTES,
        )
        verifierBytes = hkdfSha256(
            ikm = argonOutput,
            salt = ByteArray(0),
            info = AUTH_VERIFIER_LABEL.toByteArray(Charsets.US_ASCII),
            length = AuthVerifier.SIZE_BYTES,
        )
        return DerivedKeys(KeyEncryptionKey(kekBytes), AuthVerifier(verifierBytes))
    } finally {
        kekBytes?.fill(0)
        verifierBytes?.fill(0)
    }
}
