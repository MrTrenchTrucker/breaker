package dev.breaker.shared.api

/**
 * The result of a transcription job.
 *
 * Mirrors the `TranscriptionResult` schema in the OpenAPI spec.
 */
data class TranscriptionResult(
    val text: String,
    val segments: List<Segment>,
    val language: String
)
