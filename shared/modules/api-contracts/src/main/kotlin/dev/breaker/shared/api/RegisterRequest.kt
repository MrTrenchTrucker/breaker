package dev.breaker.shared.api

/**
 * The request to register a new account.
 *
 * Mirrors the `RegisterRequest` schema in the OpenAPI spec.
 * Never a password, never a DEK — the client derives the KEK and sends the verifier.
 */
data class RegisterRequest(
    val username: String,
    val salt: String,
    val kdf_params: KdfParams,
    val kdf_version: Int,
    val auth_verifier: String,
)
