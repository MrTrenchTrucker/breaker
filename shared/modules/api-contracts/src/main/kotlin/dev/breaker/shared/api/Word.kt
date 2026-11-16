package dev.breaker.shared.api

/**
 * One word of a transcription, with its own times.
 *
 * Mirrors the `Word` schema in the OpenAPI spec.
 */
data class Word(
    val word: String,
    val start: Double,
    val end: Double
)
