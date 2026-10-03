package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource

/**
 * One row of the `transcriptions` table.
 *
 * The table is the storage shape named on the module card:
 * `id, text, source (local|server), model, duration_ms, created_at, audio_path
 * (nullable, off by default)`.
 *
 * [source] is kept as the text `local` or `server` rather than as a number
 * because a row that can be read by hand is worth more than a compact one, and
 * because a stored value that is not one of the two known words is a
 * [MappingFailure] instead of a silent zero.
 *
 * [audioPath] exists so the column can be turned on later without a migration
 * that rewrites the table. It is always null today: this module does not own
 * audio, and a history row that points at an audio file the user has already
 * had deleted is worse than no path at all.
 */
internal data class TranscriptionRow(
    val id: String,
    val text: String,
    val source: String,
    val model: String,
    val durationMs: Long,
    val createdAt: Long,
    val audioPath: String? = null,
) {
    init {
        require(id.isNotBlank()) { "A history row needs a non-blank id" }
        require(model.isNotBlank()) { "A history row needs a non-blank model name" }
        require(durationMs >= 0) { "durationMs cannot be negative: $durationMs" }
        require(createdAt >= 0) { "createdAt cannot be negative: $createdAt" }
    }

    /**
     * The domain value this row stands for.
     *
     * @throws MappingFailure when the stored source is neither `local` nor
     *   `server`. A row this module cannot interpret is a defect to hear about,
     *   not a transcription to quietly drop: a transcription that vanishes from
     *   history is the one failure this module exists to prevent.
     */
    fun toTranscription(): Transcription = Transcription(
        id = id,
        text = text,
        source = toSourceOrNull()
            ?: throw MappingFailure("Row $id has an unknown source '$source'"),
        model = model,
        durationMs = durationMs,
        createdAt = createdAt,
    )

    /** The stored source as a domain value, or null when it is not one of the two. */
    fun toSourceOrNull(): TranscriptionSource? = when (source) {
        SOURCE_LOCAL -> TranscriptionSource.LOCAL
        SOURCE_SERVER -> TranscriptionSource.SERVER
        else -> null
    }

    companion object {
        const val SOURCE_LOCAL: String = "local"
        const val SOURCE_SERVER: String = "server"

        /**
         * The row for [transcription].
         *
         * Every transcription becomes a row, whichever engine produced it
         * (F7). There is no branch here that treats a server transcription
         * differently from a local one, because the day that branch appears is
         * the day a dictation disappears depending on which engine was picked.
         */
        fun of(transcription: Transcription): TranscriptionRow = TranscriptionRow(
            id = transcription.id,
            text = transcription.text,
            source = when (transcription.source) {
                TranscriptionSource.LOCAL -> SOURCE_LOCAL
                TranscriptionSource.SERVER -> SOURCE_SERVER
            },
            model = transcription.model,
            durationMs = transcription.durationMs,
            createdAt = transcription.createdAt,
            audioPath = null,
        )
    }
}

/**
 * A row in the database that this module cannot turn into a transcription.
 *
 * Thrown rather than swallowed. A history row that is dropped on read is a
 * transcription the user can no longer re-copy, and a store that hides that is
 * a store that has already lost the data.
 */
internal class MappingFailure(message: String) : IllegalStateException(message)
