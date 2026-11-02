package dev.breaker.shared.api

/**
 * One stored transcription as pulled by the account owner.
 *
 * Mirrors the `TranscriptionRecord` schema in the OpenAPI spec. Each record
 * carries its own key_version. storedAt is when the server stored the row; it
 * is not when the transcription was made, which is the created_at inside the
 * R1 body.
 */
data class TranscriptionRecord(
    val clientId: String,
    val change: Long,
    val deleted: Boolean,
    val keyVersion: Int?,
    val encrypted: String?,
    val storedAt: String,
)
