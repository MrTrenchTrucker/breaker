package dev.breaker.shared.api

/**
 * The health status of the service.
 *
 * Mirrors the `Health` schema in the OpenAPI spec.
 */
data class Health(
    val status: String,
    val forwardingTo: String,
    val uptime: String
)
