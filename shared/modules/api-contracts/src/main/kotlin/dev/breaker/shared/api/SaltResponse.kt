package dev.breaker.shared.api

/**
 * The response with an account's salt and KDF parameters.
 *
 * Mirrors the `SaltResponse` schema in the OpenAPI spec.
 */
data class SaltResponse(
    val salt: String,
    val kdfParams: KdfParams,
    val kdfVersion: Int,
)
