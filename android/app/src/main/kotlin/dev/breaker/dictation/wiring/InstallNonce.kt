package dev.breaker.dictation.wiring

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The per-session nonce of the install adapter: a random token made when an install session is opened, carried
 * in the status intent, and compared for equality in constant time. The token is lowercase hex digits, so it
 * uses only URL-safe characters. It is never written to a log.
 */
object InstallNonce {

    private const val RANDOM_BYTES: Int = 16
    private const val HEX_RADIX: Int = 16
    private val random: SecureRandom = SecureRandom()

    /** A new nonce: [RANDOM_BYTES] random bytes written as lowercase hex, so 32 characters. */
    fun generate(): String {
        val bytes = ByteArray(RANDOM_BYTES)
        random.nextBytes(bytes)
        val hex = StringBuilder(RANDOM_BYTES * 2)
        for (b in bytes) {
            val value: Int = b.toInt() and 0xff
            hex.append(Character.forDigit(value shr 4, HEX_RADIX))
            hex.append(Character.forDigit(value and 0x0f, HEX_RADIX))
        }
        return hex.toString()
    }

    /** True only when both values are present, not empty, and equal; the comparison does not stop at the first difference. */
    fun matches(expected: String?, received: String?): Boolean {
        val a: String = expected ?: return false
        val b: String = received ?: return false
        if (a.isEmpty() || b.isEmpty()) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
