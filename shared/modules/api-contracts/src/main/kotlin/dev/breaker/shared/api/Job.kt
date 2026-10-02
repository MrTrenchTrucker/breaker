package dev.breaker.shared.api

/**
 * A transcription job.
 *
 * Mirrors the `Job` schema in the OpenAPI spec.
 * The `result` field is nullable and only populated when status is DONE.
 * The `error` field carries the failure reason and is non-null exactly
 * when status is FAILED (enforced below, both ways).
 */
data class Job(
    val status: JobStatus,
    val result: TranscriptionResult? = null,
    val error: String? = null
) {
    init {
        require(status != JobStatus.DONE || result != null) {
            "Job with status DONE must have a non-null result"
        }
        require((status == JobStatus.FAILED) == (error != null)) {
            "Job error must be set exactly when status is failed"
        }
    }
}
