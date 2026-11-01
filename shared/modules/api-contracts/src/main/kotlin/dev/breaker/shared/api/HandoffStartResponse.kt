package dev.breaker.shared.api

/**
 * The response when a sign-in hand-off is started.
 *
 * Mirrors the `HandoffStartResponse` schema in the OpenAPI spec.
 * The code is single-use and short-lived.
 */
data class HandoffStartResponse(
    val code: String,
)
