package dev.breaker.shared.api

/**
 * The response when a transcription job is accepted.
 *
 * Mirrors the `JobAccepted` schema in the OpenAPI spec.
 */
data class JobAccepted(
    val jobId: String,
    val status: String = "queued"
)
