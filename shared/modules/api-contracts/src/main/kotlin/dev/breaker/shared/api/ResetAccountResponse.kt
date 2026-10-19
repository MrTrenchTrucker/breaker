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
)
