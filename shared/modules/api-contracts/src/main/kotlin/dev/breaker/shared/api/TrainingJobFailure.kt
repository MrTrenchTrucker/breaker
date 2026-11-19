package dev.breaker.shared.api

/**
 * Why a training job failed: `too_few_samples` or `training_error`. A job with too few
 * samples is a failed job, never an HTTP error.
 *
 * Mirrors the `TrainingJobFailure` schema in the OpenAPI spec.
 */
enum class TrainingJobFailure {
    TOO_FEW_SAMPLES,
    TRAINING_ERROR;

    companion object {
        /**
         * Returns the TrainingJobFailure for the given string value, or null if not found.
         */
        fun fromString(value: String): TrainingJobFailure? =
            values().find { it.name.lowercase() == value.lowercase() }
    }
}
