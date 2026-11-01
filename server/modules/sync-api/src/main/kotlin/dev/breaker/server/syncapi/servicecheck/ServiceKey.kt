package dev.breaker.server.syncapi.servicecheck

import java.security.MessageDigest

/**
 * The key the stack gives sync-api for the internal token-check routes. A blank or
 * whitespace-only key cannot be built, so an empty key can never be matched by an empty header.
 */
internal class ServiceKey(private val text: String) {
    init {
        require(text.isNotBlank())
    }

    /**
     * True only when the presented text equals the key. A missing header never matches, a
     * blank header cannot equal a key that is not blank, and the compare takes the same time
     * wherever the first differing byte sits.
     */
    fun matches(presented: String?): Boolean {
        if (presented == null) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            text.toByteArray(Charsets.UTF_8),
        )
    }
}
