package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.Transcription

/**
 * Keeps transcriptions so they can be re-read, re-copied and pushed later.
 *
 * Implemented by the history module against the phone's own database. Every
 * transcription is saved whatever produced it — local or server — so switching
 * engines never makes a dictation disappear.
 */
interface HistoryStore {
    /** Save [transcription], replacing any existing row with the same id. */
    fun save(transcription: Transcription)

    /** The [limit] most recent transcriptions, newest first. */
    fun list(limit: Int): List<Transcription>

    /** Delete one transcription. Returns true when a row was removed. */
    fun delete(id: String): Boolean
}
