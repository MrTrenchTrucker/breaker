package dev.breaker.shared.api

/**
 * The request that exchanges a sign-in code and its secret for a session.
 *
 * Mirrors the `HandoffExchangeRequest` schema in the OpenAPI spec.
 * The code and the secret are the credential; deviceName is optional.
 */
data class HandoffExchangeRequest(
    val code: String,
    val secret: String,
    val deviceName: String?,
)
