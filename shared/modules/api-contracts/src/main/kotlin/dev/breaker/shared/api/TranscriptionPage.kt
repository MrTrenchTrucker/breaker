package dev.breaker.shared.api

/**
 * One page of transcriptions pulled from the `/v1/transcriptions` endpoint.
 *
 * Mirrors the `TranscriptionPage` schema in the OpenAPI spec.
 */
data class TranscriptionPage(
    val items: List<TranscriptionRecord>,
    val next: String,
    val more: Boolean,
)
