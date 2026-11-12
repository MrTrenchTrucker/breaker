package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CryptoRefused
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The associated data that binds a wrapped box private key to its slot. A blob made for this
 * slot fails the tag check in the previous key slot, and the other way round.
 */
internal const val BOX_PRIVATE_KEY_AAD_TEXT = "breaker-box-privkey-v1"

/**
 * The associated data that binds a wrapped previous data key to its slot. Both slots hold a
 * 32 byte key sealed under the same key, so only this text keeps the two blobs apart.
 */
internal const val PREVIOUS_DEK_AAD_TEXT = "breaker-prev-dek-v1"

/** The length of a key that is wrapped, and of the key that wraps it, in bytes. */
private const val KEY_WRAP_KEY_BYTES = 32

/** The length of the AES-GCM authentication tag, in bytes. */
private const val KEY_WRAP_TAG_BYTES = 16

/** The length of the AES-GCM authentication tag, in bits, as the cipher parameters take it. */
private const val KEY_WRAP_TAG_BITS = KEY_WRAP_TAG_BYTES * 8

/** A wrapped key is exactly nonce, then the 32 key bytes sealed, then the tag: 60 bytes. */
private const val KEY_WRAP_TOTAL_BYTES = GCM_NONCE_BYTES + KEY_WRAP_KEY_BYTES + KEY_WRAP_TAG_BYTES

/** The cipher the platform provides for AES-256-GCM. */
private const val KEY_WRAP_TRANSFORMATION = "AES/GCM/NoPadding"

/** The key algorithm name the cipher expects. */
private const val KEY_WRAP_ALGORITHM = "AES"

/** The ASCII bytes of a purpose string, for use as associated data. */
internal fun aadBytes(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

/**
 * Seal a 32 byte key [plain] under the 32 byte [key] with AES-256-GCM and the associated data
 * [aad]. The result is the [nonce], then the ciphertext, then the tag: 60 bytes.
 *
 * Refuses with [CryptoRefused] when [plain] or [key] is not 32 bytes or [nonce] is not 12.
 */
internal fun sealKey(plain: ByteArray, key: ByteArray, aad: ByteArray, nonce: ByteArray): ByteArray {
    if (plain.size != KEY_WRAP_KEY_BYTES) throw CryptoRefused()
    if (key.size != KEY_WRAP_KEY_BYTES) throw CryptoRefused()
    if (nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
    val cipher = Cipher.getInstance(KEY_WRAP_TRANSFORMATION)
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(key, KEY_WRAP_ALGORITHM),
        GCMParameterSpec(KEY_WRAP_TAG_BITS, nonce),
    )
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    return nonce + cipher.doFinal(plain)
}

/**
 * Open a 60 byte [wrapped] key made by [sealKey] under the 32 byte [key] with the associated
 * data [aad], and return the 32 opened bytes.
 *
 * Refuses with [CryptoRefused] when [wrapped] is not exactly 60 bytes or [key] is not 32 bytes.
 * A wrong key, wrong associated data or a changed byte fails the cipher's own tag check; that
 * failure may propagate, and the caller turns every failure into [CryptoRefused].
 */
internal fun openKey(wrapped: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
    if (wrapped.size != KEY_WRAP_TOTAL_BYTES) throw CryptoRefused()
    if (key.size != KEY_WRAP_KEY_BYTES) throw CryptoRefused()
    val nonce = wrapped.copyOfRange(0, GCM_NONCE_BYTES)
    val cipher = Cipher.getInstance(KEY_WRAP_TRANSFORMATION)
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, KEY_WRAP_ALGORITHM),
        GCMParameterSpec(KEY_WRAP_TAG_BITS, nonce),
    )
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    return cipher.doFinal(wrapped.copyOfRange(GCM_NONCE_BYTES, wrapped.size))
}

/**
 * Seal [plain], of any length, under the 32 byte [key] with AES-256-GCM and the associated data
 * [aad]. The result is the ciphertext, then the 16 byte tag. The [nonce] is not part of it: the
 * caller keeps the nonce next to the result.
 *
 * Refuses with [CryptoRefused] when [key] is not 32 bytes or [nonce] is not 12.
 */
internal fun sealBytes(plain: ByteArray, key: ByteArray, aad: ByteArray, nonce: ByteArray): ByteArray {
    if (key.size != KEY_WRAP_KEY_BYTES) throw CryptoRefused()
    if (nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
    val cipher = Cipher.getInstance(KEY_WRAP_TRANSFORMATION)
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(key, KEY_WRAP_ALGORITHM),
        GCMParameterSpec(KEY_WRAP_TAG_BITS, nonce),
    )
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    return cipher.doFinal(plain)
}

/**
 * Open [ciphertextAndTag], the ciphertext followed by the 16 byte tag that [sealBytes] makes, under
 * the 32 byte [key] with AES-256-GCM and the associated data [aad], and return the opened bytes.
 * The [nonce] is the one the data was sealed with.
 *
 * Refuses with [CryptoRefused] when [key] is not 32 bytes, [nonce] is not 12, or fewer than 16
 * bytes are given. A wrong key, wrong associated data or a changed byte fails the cipher's own tag
 * check; that failure may propagate, and the caller turns every failure into [CryptoRefused].
 */
internal fun openBytes(
    ciphertextAndTag: ByteArray,
    key: ByteArray,
    aad: ByteArray,
    nonce: ByteArray,
): ByteArray {
    if (key.size != KEY_WRAP_KEY_BYTES) throw CryptoRefused()
    if (nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
    if (ciphertextAndTag.size < KEY_WRAP_TAG_BYTES) throw CryptoRefused()
    val cipher = Cipher.getInstance(KEY_WRAP_TRANSFORMATION)
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, KEY_WRAP_ALGORITHM),
        GCMParameterSpec(KEY_WRAP_TAG_BITS, nonce),
    )
    if (aad.isNotEmpty()) cipher.updateAAD(aad)
    return cipher.doFinal(ciphertextAndTag)
}
