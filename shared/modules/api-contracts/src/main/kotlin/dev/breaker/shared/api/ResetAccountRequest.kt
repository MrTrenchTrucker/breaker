package dev.breaker.shared.api

/**
 * A request to issue a one-time account reset code.
 *
 * Mirrors the `ResetAccountRequest` schema in the OpenAPI spec.
 */
data class ResetAccountRequest(
    val username: String,
)
