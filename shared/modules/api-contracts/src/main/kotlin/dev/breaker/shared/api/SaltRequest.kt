package dev.breaker.shared.api

/**
 * The request to look up an account's salt and KDF parameters.
 *
 * Mirrors the `SaltRequest` schema in the OpenAPI spec.
 * No decoy: an unknown username gets an honest "no such account".
 */
data class SaltRequest(
    val username: String,
)
