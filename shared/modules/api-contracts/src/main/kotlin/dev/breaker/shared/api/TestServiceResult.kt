package dev.breaker.shared.api

/**
 * The connectivity probe result for the configured transcription service.
 *
 * Mirrors the `TestServiceResult` schema in the OpenAPI spec.
 */
data class TestServiceResult(
    val reachable: Boolean,
)
