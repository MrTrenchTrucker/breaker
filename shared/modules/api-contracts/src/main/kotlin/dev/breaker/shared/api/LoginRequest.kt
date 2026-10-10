package dev.breaker.shared.api

/**
 * The request to log in.
 *
 * Mirrors the `LoginRequest` schema in the OpenAPI spec.
 * The raw auth_verifier is a login credential in its own right — never logged.
 */
data class LoginRequest(
    val username: String,
    val auth_verifier: String,
)
