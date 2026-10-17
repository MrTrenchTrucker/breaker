package dev.breaker.shared.api

/**
 * The status of a transcription job.
 *
 * Mirrors the `status` enum in the OpenAPI spec (Job schema).
 */
enum class JobStatus {
    QUEUED,
    PROCESSING,
    DONE,
    FAILED;

    companion object {
        /**
         * Returns the JobStatus for the given string value, or null if not found.
         */
        fun fromString(value: String): JobStatus? =
            values().find { it.name.lowercase() == value.lowercase() }
    }
}
