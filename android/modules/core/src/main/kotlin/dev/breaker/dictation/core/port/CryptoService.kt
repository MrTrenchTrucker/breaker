package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.CipherText
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.EncryptedText
import dev.breaker.dictation.core.model.WrappedDek

/**
 * Encrypts and decrypts text with a per-user key.
 *
 * Implemented by the crypto module. The key-derivation parameters and test
 * vectors are shared with the server and the web frontend, because all three
 * have to open the same text.
 *
 * The key itself never leaves the phone after login, and the server stores
 * only ciphertext.
 */
interface CryptoService {
    /** Open [wrappedDek] with a key derived from [password]. */
    fun unwrapDek(wrappedDek: WrappedDek, password: String): DataEncryptionKey

    /** Encrypt [plaintext] with [dek]. */
    fun encrypt(plaintext: String, dek: DataEncryptionKey): CipherText

    /** Decrypt [cipherText] with [dek]. */
    fun decrypt(cipherText: CipherText, dek: DataEncryptionKey): String

    /** The transportable form of [cipherText]. */
    fun toEncryptedText(cipherText: CipherText): EncryptedText
}
