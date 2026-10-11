package dev.breaker.shared.api

/**
 * The response when login succeeds.
 *
 * Mirrors the `LoginResponse` schema in the OpenAPI spec.
 * The four key fields are null together for a keyless account.
 */
data class LoginResponse(
    val token: String,
    val role: String,
    val wrappedDek: String?,
    val wrappedBoxPrivkey: String?,
    val boxPubkey: String?,
    val keyVersion: Int,
    val prevWrappedDek: String?,
    val resetAt: String?,
    val rekeyedAt: String?,
)
