package dev.breaker.shared.api

/**
 * The answer to a stored training sample: its id, its kind, its size in bytes and how many
 * distinct samples of that kind the user now has stored. Sending the same sample id again
 * answers the same receipt.
 *
 * Mirrors the `SampleReceipt` schema in the OpenAPI spec.
 */
data class SampleReceipt(
    val sampleId: String,
    val kind: TrainingKind,
    val sizeBytes: Int,
    val count: Int,
)
