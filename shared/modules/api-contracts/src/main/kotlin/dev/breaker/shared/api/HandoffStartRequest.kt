package dev.breaker.shared.api

/**
 * The request that starts a sign-in hand-off from the website.
 *
 * Mirrors the `HandoffStartRequest` schema in the OpenAPI spec.
 * The sealedPackage travels sealed and is never opened by the server.
 */
data class HandoffStartRequest(
    val challenge: String,
    val sealedPackage: String,
)
