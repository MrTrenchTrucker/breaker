package dev.breaker.shared.api

/**
 * Whether the wrong-password limit is on.
 *
 * Mirrors the `WrongPwLimit` schema in the OpenAPI spec.
 */
data class WrongPwLimit(
    val enabled: Boolean,
)
