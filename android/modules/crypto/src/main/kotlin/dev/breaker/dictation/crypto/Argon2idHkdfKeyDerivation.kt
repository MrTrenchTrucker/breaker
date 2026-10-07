package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.DerivedKeys
import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.port.KeyDerivation

/**
 * The Argon2id call that [Argon2idHkdfKeyDerivation] makes, as a seam. Production
 * uses the real function; a test can pass a cheap stand-in and see the call
 * without paying for 64 MiB.
 */
internal fun interface Argon2idFunction {
    /** Returns the tag for [password] and [salt] under the given cost settings. */
    fun hash(
        password: ByteArray, salt: ByteArray,
        memoryKib: Int, iterations: Int, parallelism: Int, outputLength: Int,
    ): ByteArray
}

/**
 * Implements the core [KeyDerivation] port: one Argon2id pass, then the HKDF
 * split into the key-encryption key and the login verifier.
 *
 * Before any work, and inside this class, it refuses parameters outside memory
 * 64 to 256 MiB, iterations 3 to 10 and parallelism 1 to 4, a tag length other
 * than 32 bytes, a salt that is not exactly 16 bytes and any KDF version other
 * than 1. The range checks come first, so a claimed version never relaxes a
 * range. A refusal is a `KdfRefused` that carries the reason.
 *
 * The password is normalised to Unicode NFC, then encoded as UTF-8.
 *
 * Limits to know about. The password arrives as a Kotlin
 * [String], which cannot be wiped. The Bouncy Castle implementation is pure
 * Java and is not hardened against side channels. The intermediate arrays this
 * class owns (the password bytes and the Argon2id output) are zeroed on every
 * path, including when the hash function throws. The returned [DerivedKeys] can
 * be wiped by the caller.
 *
 * A full derivation at the lowest accepted cost allocates about 64 MiB and
 * takes a noticeable fraction of a second to a few seconds, depending on the
 * device.
 */
class Argon2idHkdfKeyDerivation internal constructor(
    private val hashFunction: Argon2idFunction,
) : KeyDerivation {

    /** The production derivation, using the Bouncy Castle Argon2id. */
    constructor() : this(
        Argon2idFunction { password, salt, memoryKib, iterations, parallelism, outputLength ->
            argon2id(password, salt, memoryKib, iterations, parallelism, outputLength)
        },
    )

    override fun deriveKeys(password: String, salt: ByteArray, kdfParams: KdfParams): DerivedKeys {
        checkKdfParams(salt, kdfParams)
        val passwordBytes = passwordBytes(password)
        try {
            val tag = hashFunction.hash(
                passwordBytes, salt,
                kdfParams.memoryKib, kdfParams.iterations, kdfParams.parallelism, kdfParams.outputLength,
            )
            try {
                return splitKeys(tag)
            } finally {
                tag.fill(0)
            }
        } finally {
            passwordBytes.fill(0)
        }
    }
}
