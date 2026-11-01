package dev.breaker.shared.api

/**
 * The response when a sign-in code is exchanged.
 *
 * Mirrors the `HandoffExchangeResponse` schema in the OpenAPI spec.
 * It never carries a password or a plaintext key; the sealedPackage stays sealed.
 */
data class HandoffExchangeResponse(
    val token: String,
    val role: String,
    val keyVersion: Int,
    val sealedPackage: String,
    val username: String,
    val accountId: Long,
)
