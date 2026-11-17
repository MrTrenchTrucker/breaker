package dev.breaker.shared.api

/**
 * The health status of the service.
 *
 * Mirrors the `Health` schema in the OpenAPI spec: `status` is required, the other field is
 * optional (whisper-server sends `uptime`, sync-api sends only `status`).
 */
data class Health(
    val status: String,
    val uptime: String? = null
)
