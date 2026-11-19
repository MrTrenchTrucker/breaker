package dev.breaker.shared.api

/**
 * Where a training job is: `queued`, `running`, `done` or `failed`.
 *
 * Mirrors the `TrainingJobState` schema in the OpenAPI spec.
 */
enum class TrainingJobState {
    QUEUED,
    RUNNING,
    DONE,
    FAILED;

    companion object {
        /**
         * Returns the TrainingJobState for the given string value, or null if not found.
         */
        fun fromString(value: String): TrainingJobState? =
            values().find { it.name.lowercase() == value.lowercase() }
    }
}
