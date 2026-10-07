package dev.breaker.shared.api

/**
 * The response when a transcription job is accepted.
 *
 * Mirrors the `JobAccepted` schema in the OpenAPI spec. The spec carries a
 * JobStatus $ref narrowed to the single value [queued] by a sibling enum;
 * the Kotlin side keeps the same narrowing at construction: a job is
 * accepted as queued and nothing else.
 */
data class JobAccepted(
    val jobId: String,
    val status: JobStatus = JobStatus.QUEUED
) {
    init {
        require(status == JobStatus.QUEUED) {
            "A job is accepted only as queued, got $status"
        }
    }
}
