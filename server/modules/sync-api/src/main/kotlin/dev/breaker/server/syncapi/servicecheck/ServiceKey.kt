package dev.breaker.server.syncapi.servicecheck

import java.security.MessageDigest

/**
 * The key the stack gives sync-api for the internal token-check routes. A blank or
 * whitespace-only key cannot be built, so an empty key can never be matched by an empty header.
 * Open: a test may count the compare to prove the null/blank/wrong-key paths all run it.
 */
internal open class ServiceKey(private val text: String) {
    init {
        require(text.isNotBlank())
    }

    /**
     * True only when the presented text equals the key. A missing header never matches, a
     * blank header cannot equal a key that is not blank, and the compare takes the same time
     * wherever the first differing byte sits. A missing header is not an early return: it is
     * compared against a fixed-length dummy of the key's own byte length, so null, blank and
     * wrong-key all run the same constant-time compare and cost the same.
     */
    open fun matches(presented: String?): Boolean {
        val keyBytes = text.toByteArray(Charsets.UTF_8)
        // The dummy is the key's own byte length, so the compare is the same shape for a
        // missing header as for a wrong one.
        val presentedBytes = presented?.toByteArray(Charsets.UTF_8) ?: ByteArray(keyBytes.size)
        return compare(presentedBytes, keyBytes)
    }

    // Open seam: the constant-time compare. A test counts it to prove every presented path
    // (null, blank, wrong, exact) runs it - a boolean assertion alone cannot tell a compare
    // from an early return.
    open fun compare(presented: ByteArray, key: ByteArray): Boolean =
        MessageDigest.isEqual(presented, key)
}
