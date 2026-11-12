package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.BoxKeyPair
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedAgentResult
import dev.breaker.dictation.core.model.WrappedBoxPrivateKey
import dev.breaker.dictation.core.model.WrappedDek
import dev.breaker.dictation.core.model.WrappedPreviousDek

/**
 * Makes and wraps the account's keys: the data key, the box key pair and their wrapped
 * forms.
 *
 * Implemented by the crypto module. The port never sees a password and never derives a
 * key; the caller hands the key-encryption key in, as for [CryptoService]. The keys never
 * leave the phone unwrapped.
 *
 * Every operation that cannot be completed throws [dev.breaker.dictation.core.model.CryptoRefused]
 * and returns nothing partial. The message carries no secret. Each wrapped box key and each
 * wrapped previous key is bound to its purpose, so the wrong blob in the wrong slot is
 * refused.
 */
interface AccountKeyService {
    /**
     * Make a new data key from the secure random source.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when the key cannot be made.
     * The caller owns the returned key and wipes it when done.
     */
    fun generateDataKey(): DataEncryptionKey

    /**
     * Make a new box key pair from the secure random source.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when the pair cannot be made.
     * The caller owns the returned pair and wipes it with [BoxKeyPair.wipe].
     */
    fun generateBoxKeyPair(): BoxKeyPair

    /**
     * Seal [dek] under [kek], the key-encryption key the CALLER derived, with empty
     * associated data. [salt] is carried into the returned [WrappedDek] and is not used by
     * the wrap; the result holds its own copy of it.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when the wrap cannot be made.
     * The caller owns the returned value.
     */
    fun wrapDek(dek: DataEncryptionKey, kek: KeyEncryptionKey, salt: ByteArray): WrappedDek

    /**
     * Seal [boxPrivateKey] under [dek]. The result opens only in the box key slot.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when the wrap cannot be made.
     * The caller owns the returned value.
     */
    fun wrapBoxPrivateKey(boxPrivateKey: BoxPrivateKey, dek: DataEncryptionKey): WrappedBoxPrivateKey

    /**
     * Open [wrapped] with [dek] and return the box private key.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when [dek] is the wrong key,
     * [wrapped] is not 60 bytes, was changed, or belongs to the other slot. The caller owns
     * the returned key and wipes it.
     */
    fun unwrapBoxPrivateKey(wrapped: WrappedBoxPrivateKey, dek: DataEncryptionKey): BoxPrivateKey

    /**
     * Seal [previousDek] under [newDek]. The result opens only in the previous key slot.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when the wrap cannot be made.
     * The caller owns the returned value.
     */
    fun wrapPreviousDek(previousDek: DataEncryptionKey, newDek: DataEncryptionKey): WrappedPreviousDek

    /**
     * Open [wrapped] with [newDek] and return the previous data key.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when [newDek] is the wrong
     * key, [wrapped] is not 60 bytes, was changed, or belongs to the other slot. The caller
     * owns the returned key and wipes it.
     */
    fun unwrapPreviousDek(wrapped: WrappedPreviousDek, newDek: DataEncryptionKey): DataEncryptionKey

    /**
     * Recompute the public key from [boxPrivateKey] and compare it with [publicKey] in
     * constant time. Returns normally when they match.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when they differ or
     * [publicKey] is not 32 bytes. A mismatch means the stored public key was tampered
     * with: the caller must surface it, never silently replace it.
     */
    fun verifyBoxPublicKey(boxPrivateKey: BoxPrivateKey, publicKey: ByteArray)

    /**
     * Open [sealed] with [boxPrivateKey] and encrypt the plaintext again under [dek] with
     * [aad] as the associated data.
     *
     * The plaintext must be an R1 body with source server and at most 524288 bytes. The
     * checks run in this order and the first failure refuses: the size caps, the open, the
     * plaintext checks, then the re-encryption. The SAME validated plaintext bytes are
     * encrypted under [dek] with [aad] and one fresh nonce; nothing is re-serialised. The
     * result is the ciphertext, its nonce and its tag.
     *
     * Every plaintext copy is zeroed on every path, and no partial result is ever returned.
     * The nonce is made only after every check has passed.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] on every failure, with no
     * reason and no body text. The caller owns the returned value.
     */
    fun unsealAndReencrypt(
        sealed: SealedAgentResult,
        boxPrivateKey: BoxPrivateKey,
        dek: DataEncryptionKey,
        aad: ByteArray,
    ): CipherText

    /**
     * Open [cipherText] under [oldDek] with [oldAad] and encrypt the SAME plaintext bytes again
     * under [newDek] with [newAad] and one fresh nonce. Nothing is turned into a String or
     * re-serialised, so any bytes survive. The associated data is opaque bytes.
     *
     * The checks run in this order and the first failure refuses: the sizes (a ciphertext above
     * 524288 bytes, a key that is not 32 bytes, a nonce that is not 12, a tag that is not 16),
     * the open (the tag is checked before any plaintext byte is used), then the re-encryption.
     * The nonce is made only after the open succeeded.
     *
     * Every plaintext copy is zeroed on every path, and no partial result is ever returned.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] on every failure, with no
     * reason and no body text. The caller owns the returned value.
     */
    fun reencrypt(
        cipherText: CipherText,
        oldDek: DataEncryptionKey,
        oldAad: ByteArray,
        newDek: DataEncryptionKey,
        newAad: ByteArray,
    ): CipherText

    /**
     * Seal [plain], opaque bytes of any content, under [dek] with [aad] as the associated data
     * (AES-256-GCM, a 12-byte nonce from the nonce source, a 16-byte tag). Nothing is turned into
     * a String and no format is checked. An empty [plain] is valid.
     *
     * The checks run in this order and the first failure refuses: the size caps (a plaintext above
     * 524288 bytes, a key that is not 32 bytes), then one nonce, which must be 12 bytes, then the
     * encryption. The nonce is made only after the size checks have passed.
     *
     * The caller keeps [plain] and wipes it; the service never writes to it. Every internal copy is
     * zeroed on every path, and no partial result is ever returned.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] on every failure, with no cause and
     * no text of any body or key. The caller owns the returned value.
     */
    fun encryptBytes(plain: ByteArray, dek: DataEncryptionKey, aad: ByteArray): CipherText

    /**
     * Open [cipherText] under [dek] with [aad] and return the plaintext bytes as one array. The
     * bytes are opaque: nothing is turned into a String and no format is checked. No nonce is
     * asked for.
     *
     * The checks run in this order and the first failure refuses: the sizes (a ciphertext above
     * 524288 bytes, a key that is not 32 bytes, a nonce that is not 12, a tag that is not 16),
     * then the open, where the tag is checked before any plaintext byte is used.
     *
     * The caller owns the returned array and wipes it. On any failure nothing is returned.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] on every failure, with no cause and
     * no text of any body or key.
     */
    fun decryptBytes(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): ByteArray
}
