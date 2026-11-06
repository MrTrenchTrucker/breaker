package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.StoredAccountKeys

/**
 * Where the account keys are kept on the phone between sign-ins. It holds the keys only; the
 * session is kept elsewhere, by the auth client's session vault. The app implements it with a sealed file.
 *
 * No member ever logs or prints a key or a version.
 */
interface CredentialVault {
    /**
     * Store the data-encryption key and the box private key of [keys] with [keyVersion],
     * replacing any earlier keys. It copies what it is given, so the caller wipes its own
     * copy right after the call. It throws when it cannot store.
     */
    fun saveKeys(keys: AccountKeys, keyVersion: Int)

    /**
     * The stored keys with their version, or null when nothing is stored or the store cannot be
     * read. Every call returns a new holder (a copy); the caller owns it and wipes it when done.
     */
    fun readKeys(): StoredAccountKeys?

    /**
     * Remove the stored keys and their version. It is safe when nothing is stored, and it throws
     * when it cannot remove.
     */
    fun wipeAll()
}
