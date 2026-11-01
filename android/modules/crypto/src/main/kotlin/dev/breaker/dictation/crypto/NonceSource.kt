package dev.breaker.dictation.crypto

import java.security.SecureRandom

/** The nonce length AES-GCM uses here, in bytes (96 bits). */
internal const val GCM_NONCE_BYTES = 12

/**
 * Where the nonces for AES-GCM come from.
 *
 * Every nonce the service uses comes only from here, so that no caller can pick one. In
 * production the source is [SecureRandomNonceSource]. A test supplies a fixed source only to
 * reproduce a known-answer vector; a fixed source anywhere else would repeat a nonce under
 * one key, which breaks AES-GCM.
 */
internal fun interface NonceSource {
    /** A fresh nonce of [GCM_NONCE_BYTES] bytes. The caller owns the returned array. */
    fun next(): ByteArray
}

/**
 * The production [NonceSource]: random bytes from one [SecureRandom].
 *
 * It holds a single generator for its whole life, so it is not re-seeded or re-created on
 * every call.
 */
internal class SecureRandomNonceSource : NonceSource {
    private val random = SecureRandom()

    override fun next(): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES)
        random.nextBytes(nonce)
        return nonce
    }
}
