package dev.breaker.shared.api

/**
 * The one-time account reset code.
 *
 * Mirrors the `ResetAccountResponse` schema in the OpenAPI spec. Returned only
 * here; it carries at least 128 bits of entropy, is single use, and is valid for
 * 24 hours.
 */
data class ResetAccountResponse(
    val resetCode: String,
) {
    init {
        require(resetCode.isNotBlank() && resetCode.none { it.isWhitespace() }) {
            "resetCode must be non-blank with no whitespace"
        }
        require(resetCode.length <= 64) {
            "resetCode must be at most 64 chars"
        }
    }
}
