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
    val wrapped_dek: String?,
    val wrapped_box_privkey: String?,
    val box_pubkey: String?,
    val key_version: Int,
    val prev_wrapped_dek: String?,
    val reset_at: String?,
    val rekeyed_at: String?,
)
