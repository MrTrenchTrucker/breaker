package dev.breaker.dictation.updater

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/** Pure APK digest check: SHA-256 of [bytes] compared with [expectedHex], case-insensitive. */
internal object ApkDigest {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /**
     * True when the SHA-256 digest of [bytes] (lowercase hex) equals [expectedHex],
     * compared case-insensitively. A malformed or wrong-length [expectedHex] is false;
     * this never throws. The empty array is a valid input.
     */
    fun matches(bytes: ByteArray, expectedHex: String): Boolean {
        val digest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            return false
        }
        return matchesDigest(digest.digest(bytes), expectedHex)
    }

    /** True when [digest] (already-computed SHA-256 bytes) equals [expectedHex] (lowercase hex), case-insensitive. A malformed or wrong-length [expectedHex] is false; this never throws. The empty array is a valid input. */
    fun matchesDigest(digest: ByteArray, expectedHex: String): Boolean {
        val computed = toHex(digest)
        return computed.equals(expectedHex, ignoreCase = true)
    }

    private fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[v ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(chars)
    }
}
