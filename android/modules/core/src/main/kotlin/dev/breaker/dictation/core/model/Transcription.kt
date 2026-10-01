package dev.breaker.dictation.core.model

/**
 * Which engine produced a [Transcription].
 *
 * `LOCAL` means the phone transcribed the audio itself; `SERVER` means the
 * Local Server did. The value is recorded on every transcription so history and
 * sync can show which path the text came from, whatever the routing decided at
 * the time.
 */
enum class TranscriptionSource { LOCAL, SERVER }

/**
 * One dictation, as the domain stores it.
 *
 * This is the value the history store keeps and the sync queue pushes. The
 * identifier is minted by whoever created the transcription (the dictation flow
 * does it through an id source), so the domain never invents identifiers on its
 * own and tests stay deterministic.
 *
 * [text] is the text the user asked to have inserted, i.e. after formatting.
 * Blank text is allowed and is not an error: recording silence transcribes to
 * nothing, and the domain does not decide what counts as worth keeping.
 *
 * [durationMs] and [createdAt] are plain millisecond counts — epoch
 * milliseconds for the latter — so this type carries no clock or calendar type
 * into the domain.
 */
data class Transcription(
    val id: String,
    val text: String,
    val source: TranscriptionSource,
    val model: String,
    val durationMs: Long,
    val createdAt: Long,
) {
    init {
        require(id.isNotBlank()) { "A transcription needs a non-blank id" }
        require(model.isNotBlank()) { "A transcription needs a non-blank model name" }
        require(durationMs >= 0) { "durationMs cannot be negative: $durationMs" }
        require(createdAt >= 0) { "createdAt cannot be negative: $createdAt" }
    }

    /**
     * Prints the ids, source and timing and the length of the text, never the
     * text: what the user said is private, and a value that reaches a log line
     * or a crash report must not carry it.
     */
    override fun toString(): String =
        "Transcription(id=$id, source=$source, model=$model, ${text.length} chars, " +
            "durationMs=$durationMs, createdAt=$createdAt)"
}
