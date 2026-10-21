package dev.breaker.shared.api.sync

/**
 * One transcription as the sync body carries it. The codec checks the values
 * when it encodes or decodes; this class holds them as given.
 */
data class SyncBody(
    val text: String,
    val source: SyncSource,
    val model: String,
    val durationMs: Long,
    val createdAtMs: Long,
)
