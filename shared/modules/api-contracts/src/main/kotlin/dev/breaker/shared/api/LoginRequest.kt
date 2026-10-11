package dev.breaker.shared.api

/**
 * The request to log in.
 *
 * Mirrors the `LoginRequest` schema in the OpenAPI spec.
 * The raw authVerifier is a login credential in its own right — never logged.
 */
data class LoginRequest(
    val username: String,
    val authVerifier: String,
)
