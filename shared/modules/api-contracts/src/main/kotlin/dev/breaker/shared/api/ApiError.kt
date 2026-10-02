package dev.breaker.shared.api

/**
 * The error body returned by the API (401/404/413).
 *
 * Mirrors the `Error` schema in the OpenAPI spec. Named ApiError so it
 * cannot clash with kotlin.Error.
 */
data class ApiError(
    val error: String,
    val message: String
)
