package dev.breaker.shared.api

/**
 * A transcription job.
 *
 * Mirrors the `Job` schema in the OpenAPI spec.
 * The `result` field is nullable and only populated when status is DONE.
 */
data class Job(
    val status: JobStatus,
    val result: TranscriptionResult? = null
) {
    init {
        require(status != JobStatus.DONE || result != null) {
            "Job with status DONE must have a non-null result"
        }
    }
}
