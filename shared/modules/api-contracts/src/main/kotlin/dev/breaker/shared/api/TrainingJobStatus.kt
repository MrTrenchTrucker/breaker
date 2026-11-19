package dev.breaker.shared.api

/**
 * The status of a training job. The `failure` field is present exactly when the state is FAILED
 * (enforced below): a failed job always carries its reason and a job that is not failed carries none.
 *
 * Mirrors the `TrainingJobStatus` schema in the OpenAPI spec.
 */
data class TrainingJobStatus(
    val jobId: String,
    val state: TrainingJobState,
    val failure: TrainingJobFailure? = null,
) {
    init {
        require((state == TrainingJobState.FAILED) == (failure != null)) {
            "TrainingJobStatus failure must be set exactly when state is failed"
        }
    }
}
