package dev.breaker.shared.api

/**
 * The request to create an agent token.
 *
 * Mirrors the `AgentTokenCreateRequest` schema in the OpenAPI spec.
 * Scope is optional: null or omitted, the server reads it as `transcribe`.
 */
data class AgentTokenCreateRequest(
    val name: String,
    val ownerUsername: String,
    val scope: AgentTokenScope? = null,
    val expiresAt: String?,
)
