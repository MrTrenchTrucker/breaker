package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams

/**
 * Turns the account password into the two values the app needs: the key-encryption
 * key and the login verifier.
 *
 * Implemented by the crypto module. Both values come out of one call, and no
 * function anywhere returns the key-encryption key alone. That way nothing that can
 * compute the verifier without the password can also produce the key. The key never
 * leaves the phone and the password is never sent; only the verifier goes to the
 * server.
 *
 * An implementation decides for itself which parameters it accepts. Callers do not
 * have to enforce limits themselves: the stored values can be wrong or hostile, so
 * the implementation checks them, in the one place that does the work.
 */
interface KeyDerivation {
    /**
     * Derive the key-encryption key and the login verifier from [password], [salt]
     * and [kdfParams], in one call that returns both.
     *
     * Throws [dev.breaker.dictation.core.model.KdfRefused] when the parameters or the
     * salt are not acceptable, before doing any derivation work.
     *
     * About the password type: a Kotlin [String] cannot be wiped. It stays in memory
     * until it is garbage-collected. The returned [DerivedKeys] can be wiped by the
     * caller with [DerivedKeys.wipe].
     */
    fun deriveKeys(password: String, salt: ByteArray, kdfParams: KdfParams): DerivedKeys
}
