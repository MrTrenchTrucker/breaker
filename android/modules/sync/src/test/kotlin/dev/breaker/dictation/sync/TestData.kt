package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource

/**
 * A transcription with distinctive default text, so a test can tell it apart from any other
 * transcription. Every argument has a default, so a test names only the field it cares about.
 */
internal fun aTranscription(
    id: String,
    text: String = "quartz marmalade for $id",
    source: TranscriptionSource = TranscriptionSource.LOCAL,
    model: String = "small.en",
    durationMs: Long = 1_200,
    createdAt: Long = 1_700_000_000_000,
): Transcription = Transcription(
    id = id,
    text = text,
    source = source,
    model = model,
    durationMs = durationMs,
    createdAt = createdAt,
)

/**
 * A lowercase canonical UUID (8-4-4-4-12) built from [n], so a test can name a client id that the
 * drain accepts. clientId(1) is 00000001-0000-4000-8000-000000000001.
 */
internal fun clientId(n: Int): String = String.format("%08x-0000-4000-8000-%012x", n, n)

/** What [block] threw, or null when it returned. An [Error] counts, so a guard failure shows up. */
internal fun thrownBy(block: () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }
