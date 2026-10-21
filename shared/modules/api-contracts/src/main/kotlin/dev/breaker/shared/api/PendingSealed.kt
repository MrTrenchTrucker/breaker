package dev.breaker.shared.api

/**
 * A still-sealed agent-token job result awaiting the project owner's unseal and
 * re-upload.
 *
 * Mirrors the `PendingSealed` schema in the OpenAPI spec.
 */
data class PendingSealed(
    val id: String,
    val sealedBox: String,
    val createdAt: String,
)
