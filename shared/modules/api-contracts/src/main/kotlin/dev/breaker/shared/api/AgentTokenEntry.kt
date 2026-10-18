package dev.breaker.shared.api

/**
 * A listing entry for an agent token (no token value).
 *
 * Mirrors the `AgentTokenEntry` schema in the OpenAPI spec.
 */
data class AgentTokenEntry(
    val id: Long,
    val name: String,
    val ownerUsername: String,
    val scope: AgentTokenScope,
    val expiresAt: String?,
)
