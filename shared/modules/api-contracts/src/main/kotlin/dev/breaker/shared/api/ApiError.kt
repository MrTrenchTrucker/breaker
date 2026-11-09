package dev.breaker.shared.api

/**
 * The error body returned by the API. Mirrors the `Error` schema in the
 * OpenAPI spec: `error` is always present, `message` is optional and is
 * absent on the wire when the server sends only `error`. Named ApiError so
 * it cannot clash with kotlin.Error.
 */
data class ApiError(
    val error: String,
    val message: String? = null
)
