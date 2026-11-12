package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.CryptoRefused
import dev.breaker.dictation.core.model.DataEncryptionKey

/**
 * Seal [plain], opaque bytes of any content, under [dek] with the associated data [aad], and split
 * the result into the ciphertext and the tag. The order, and the first failure refuses: the
 * plaintext cap, the key size, then one nonce from [nonces] (which must be 12 bytes), then the
 * encryption.
 *
 * The caller's [plain] is never written. The sealed copy made here is zeroed on every path. Nothing
 * is turned into text and no format of the bytes is checked.
 *
 * Refuses with [CryptoRefused]; a failure of the cipher itself propagates for the caller to turn
 * into [CryptoRefused].
 */
internal fun encryptRecordBytes(
    plain: ByteArray,
    dek: DataEncryptionKey,
    aad: ByteArray,
    nonces: NonceSource,
): CipherText {
    if (plain.size > UNSEAL_PLAINTEXT_MAX_BYTES) throw CryptoRefused()
    if (dek.bytes.size != ACCOUNT_KEY_BYTES) throw CryptoRefused()
    val nonce = nonces.next()
    if (nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
    val sealedBytes = sealBytes(plain, dek.bytes, aad, nonce)
    return try {
        val tagStart = sealedBytes.size - UNSEAL_TAG_BYTES
        CipherText(
            sealedBytes.copyOfRange(0, tagStart),
            nonce.copyOf(),
            sealedBytes.copyOfRange(tagStart, sealedBytes.size),
        )
    } finally {
        sealedBytes.fill(0)
    }
}

/**
 * Open [cipherText] under [dek] with the associated data [aad] and return the plaintext bytes as one
 * array that the caller owns and wipes. The order, and the first failure refuses: the ciphertext
 * cap, the key size, the nonce size, the tag size, then the open, where the cipher checks the tag
 * before any plaintext byte is used. No nonce source is involved.
 *
 * The joined input made here is zeroed on every path. Nothing is turned into text and no format of
 * the bytes is checked.
 *
 * Refuses with [CryptoRefused]; a failure of the cipher itself propagates for the caller to turn
 * into [CryptoRefused].
 */
internal fun decryptRecordBytes(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): ByteArray {
    if (cipherText.ciphertext.size > UNSEAL_PLAINTEXT_MAX_BYTES) throw CryptoRefused()
    if (dek.bytes.size != ACCOUNT_KEY_BYTES) throw CryptoRefused()
    if (cipherText.nonce.size != GCM_NONCE_BYTES) throw CryptoRefused()
    if (cipherText.tag.size != UNSEAL_TAG_BYTES) throw CryptoRefused()
    val sealedInput = cipherText.ciphertext + cipherText.tag
    return try {
        openBytes(sealedInput, dek.bytes, aad, cipherText.nonce)
    } finally {
        sealedInput.fill(0)
    }
}
