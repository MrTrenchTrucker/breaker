package dev.breaker.shared.api

/**
 * A request to grant or revoke an account's admin role.
 *
 * Mirrors the `RoleUpdateRequest` schema in the OpenAPI spec.
 */
data class RoleUpdateRequest(
    val username: String,
    val admin: Boolean,
)
