package dev.breaker.dictation.seal

import java.security.SecureRandom

/** The length of an ephemeral private key, in bytes (an X25519 key). */
private const val EPHEMERAL_PRIVATE_KEY_BYTES = 32

/**
 * Where the ephemeral private keys of sealed boxes come from.
 *
 * The ephemeral key is the only randomness a sealed box has, so it is the one seam: a test
 * supplies a fixed key to reproduce a known-answer vector byte for byte, and a fixed key
 * anywhere else would make every box to the same recipient identical. In production the source
 * is [SecureRandomEphemeralSource].
 */
internal fun interface EphemeralSource {
    /**
     * A fresh ephemeral private key of [EPHEMERAL_PRIVATE_KEY_BYTES] bytes. The caller owns the
     * returned array and zeroes it after use, so an implementation must hand out a new array
     * rather than one it keeps.
     */
    fun next(): ByteArray
}

/**
 * The production [EphemeralSource]: random bytes from one [SecureRandom].
 *
 * It holds a single generator for its whole life, so it is not re-seeded or re-created on
 * every call.
 */
internal class SecureRandomEphemeralSource : EphemeralSource {
    private val random = SecureRandom()

    override fun next(): ByteArray {
        val key = ByteArray(EPHEMERAL_PRIVATE_KEY_BYTES)
        random.nextBytes(key)
        return key
    }
}
