package dev.breaker.shared.api

/**
 * A request to configure the transcription service.
 *
 * Mirrors the `TranscriptionServiceUpdate` schema in the OpenAPI spec. The API
 * key is optional and write-only; omit or null to leave the stored key unchanged.
 */
data class TranscriptionServiceUpdate(
    val name: String,
    val baseUrl: String,
    val enabled: Boolean,
    val apiKey: String?,
)
