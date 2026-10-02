package dev.breaker.shared.api

/**
 * A segment of a transcription.
 *
 * Mirrors the `Segment` schema in the OpenAPI spec.
 */
data class Segment(
    val start: Double,
    val end: Double,
    val text: String
)
