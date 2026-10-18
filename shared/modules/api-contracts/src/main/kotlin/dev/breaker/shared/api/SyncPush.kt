package dev.breaker.shared.api

/**
 * The body of a transcription push.
 *
 * Mirrors the `SyncPush` schema in the OpenAPI spec. Idempotent on the pair
 * (client_id, key_version), not on client_id alone.
 */
data class SyncPush(
    val clientId: String,
    val keyVersion: Int,
    val encrypted: String,
)
