package dev.breaker.shared.api

/**
 * One stored transcription as pulled by the account owner.
 *
 * Mirrors the `TranscriptionRecord` schema in the OpenAPI spec. Each record
 * carries its own key_version.
 */
data class TranscriptionRecord(
    val clientId: String,
    val keyVersion: Int,
    val encrypted: String,
    val createdAt: String,
)
