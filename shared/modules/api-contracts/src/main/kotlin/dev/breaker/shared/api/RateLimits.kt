package dev.breaker.shared.api

/**
 * Whether the rate limits on the open sync-api routes are on.
 *
 * Mirrors the `RateLimits` schema in the OpenAPI spec.
 */
data class RateLimits(
    val enabled: Boolean,
)
