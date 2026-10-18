package dev.breaker.server.syncapi.accounts

import java.security.MessageDigest
import java.security.SecureRandom

/** What the database keeps in place of the verifier: algorithm, cost, salt and the derived hash. */
internal class StoredVerifier(val algo: String, val iterations: Int, val salt: ByteArray, val hash: ByteArray)

internal class VerifierHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(iterations >= 1) { "sync-api: the verifier hasher needs at least one iteration" }
    }

    fun hashNew(verifier: AuthVerifier): StoredVerifier {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return StoredVerifier(ALGO, iterations, salt, derive(verifier, salt, iterations))
    }

    // The stored cost and salt decide the derivation, not this hasher's own count, so
    // accounts hashed under an older cost keep working after the default is raised.
    fun matches(verifier: AuthVerifier, stored: StoredVerifier): Boolean {
        if (stored.algo != ALGO) {
            return false
        }
        val derived = derive(verifier, stored.salt, stored.iterations)
        // isEqual takes the same time wherever the first differing byte sits.
        return MessageDigest.isEqual(derived, stored.hash)
    }

    private fun derive(verifier: AuthVerifier, salt: ByteArray, count: Int): ByteArray {
        val raw = verifier.copyBytes()
        try {
            return pbkdf2HmacSha256(raw, salt, count)
        } finally {
            raw.fill(0)
        }
    }

    companion object {
        const val ALGO = "pbkdf2-hmac-sha256"
        const val DEFAULT_ITERATIONS = 600_000
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
    }
}
