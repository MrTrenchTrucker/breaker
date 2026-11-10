package dev.breaker.shared.api

/**
 * The health status of the service.
 *
 * Mirrors the `Health` schema in the OpenAPI spec: `status` is required, the other two fields are
 * optional (whisper-server sends both, sync-api sends only `status`).
 */
data class Health(
    val status: String,
    val forwardingTo: String? = null,
    val uptime: String? = null
)
