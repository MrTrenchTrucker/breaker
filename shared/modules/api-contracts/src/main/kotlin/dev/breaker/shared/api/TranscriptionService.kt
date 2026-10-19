package dev.breaker.shared.api

/**
 * The configured transcription service as read by the admin.
 *
 * Mirrors the `TranscriptionService` schema in the OpenAPI spec. The API key is
 * never echoed; `apiKeySet` reports whether one has been stored.
 */
data class TranscriptionService(
    val name: String,
    val baseUrl: String,
    val apiKeySet: Boolean,
    val enabled: Boolean,
)
