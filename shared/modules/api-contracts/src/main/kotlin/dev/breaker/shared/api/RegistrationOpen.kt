package dev.breaker.shared.api

/**
 * Whether registration is open.
 *
 * Mirrors the `RegistrationOpen` schema in the OpenAPI spec.
 */
data class RegistrationOpen(
    val enabled: Boolean,
)
