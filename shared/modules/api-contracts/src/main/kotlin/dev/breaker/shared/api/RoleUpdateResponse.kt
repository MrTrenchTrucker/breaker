package dev.breaker.shared.api

/**
 * The account's role after an update.
 *
 * Mirrors the `RoleUpdateResponse` schema in the OpenAPI spec. The role string
 * reuses the wording of the login response field ("admin" or "user").
 */
data class RoleUpdateResponse(
    val username: String,
    val role: String,
)
