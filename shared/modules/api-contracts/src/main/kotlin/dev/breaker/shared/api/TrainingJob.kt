package dev.breaker.shared.api

/**
 * A training job that was started, or the one already queued or running that a second
 * start answers with.
 *
 * Mirrors the `TrainingJob` schema in the OpenAPI spec.
 */
data class TrainingJob(
    val jobId: String,
    val state: TrainingJobState,
)
