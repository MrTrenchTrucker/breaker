package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.KeyEncryptionKey
import dev.breaker.dictation.core.model.SealedSignInPackage
import dev.breaker.dictation.core.model.SignInPrivateKey
import dev.breaker.dictation.core.model.WrappedDek

/**
 * Opens the account's keys and encrypts and decrypts text with them.
 *
 * Implemented by the crypto module. The record format and test vectors are shared with
 * the server and the web frontend, because all three have to open the same text.
 *
 * The port never sees a password and never derives a key. The caller gets the
 * key-encryption key from the separate key-derivation port and hands it in. That way a
 * device that never typed the password, because the website signed it in, uses the same
 * port as one that did.
 *
 * Every operation that cannot be completed throws [dev.breaker.dictation.core.model.CryptoRefused]
 * and returns nothing: no partial key and no partial text. The message carries no secret.
 *
 * The keys never leave the phone after login, and the server stores only ciphertext.
 */
interface CryptoService {
    /**
     * Open [wrappedDek] with [kek], the key-encryption key the CALLER derived. It is
     * never a password; this port does not derive keys.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when [kek] is the wrong
     * key or [wrappedDek] is not a well-formed wrapped key.
     */
    fun unwrapDek(wrappedDek: WrappedDek, kek: KeyEncryptionKey): DataEncryptionKey

    /**
     * Open [sealedPackage], the key package the website sealed for this sign-in, with
     * [signInPrivateKey], the key the app made for it, and return the account's keys.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when the package does not
     * open with that key, was changed, or does not hold the account's keys. The caller
     * owns the returned [AccountKeys] and wipes them with [AccountKeys.wipe].
     */
    fun openSignInPackage(sealedPackage: SealedSignInPackage, signInPrivateKey: SignInPrivateKey): AccountKeys

    /**
     * Encrypt [plaintext] with [dek]. [aad] is the additional authenticated data: it is
     * not stored in the result, but it is bound to it, so [decrypt] opens the text only
     * when it is given the same bytes. That ties a stored text to the record it belongs to.
     */
    fun encrypt(plaintext: String, dek: DataEncryptionKey, aad: ByteArray): CipherText

    /**
     * Decrypt [cipherText] with [dek] and the same [aad] that [encrypt] was given.
     *
     * Throws [dev.breaker.dictation.core.model.CryptoRefused] when the key or the
     * additional data is wrong or the ciphertext was changed, and returns no text then.
     */
    fun decrypt(cipherText: CipherText, dek: DataEncryptionKey, aad: ByteArray): String

    /** The transportable form of [cipherText]. */
    fun toEncryptedText(cipherText: CipherText): EncryptedText
}
