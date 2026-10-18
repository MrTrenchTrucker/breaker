package dev.breaker.shared.api

/**
 * An agent token, as returned by create (and never again by list).
 *
 * Mirrors the `AgentToken` schema in the OpenAPI spec.
 */
data class AgentToken(
    val token: String,
    val name: String,
    val ownerUsername: String,
    val scope: AgentTokenScope,
    val expiresAt: String?,
)
