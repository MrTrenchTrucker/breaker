package dev.breaker.shared.api

/**
 * The response when a new account is registered.
 *
 * Mirrors the `RegisterResponse` schema in the OpenAPI spec.
 */
data class RegisterResponse(
    val username: String,
    val role: String,
)
