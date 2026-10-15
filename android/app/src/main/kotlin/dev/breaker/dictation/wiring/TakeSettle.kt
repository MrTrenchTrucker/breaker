package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.port.HistoryStore

/** What core answers with for a take that held no audio (a double tap): it is not a failure. */
internal const val EMPTY_TAKE_SENTENCE: String = "No audio was captured"

/** The line each outcome of a settled take answers with. One set per kind of take; the words are the caller's. */
internal class OutcomeLines(
    val savedAndCopied: String,
    val copiedNotSaved: String,
    val blank: String,
    val noAudio: String,
    val notConverted: String,
)

/**
 * The settle that every take the app ends by itself shares: the background saves the text to History,
 * and the main thread copies it to the clipboard and answers one outcome line. The text is never typed
 * into the field here.
 */
internal class TakeSettle(private val history: HistoryStore?, private val clipboard: TakenClipboard) {

    /** Runs on the background. True when the history save threw; only non-blank text is saved, and only with a store. */
    fun save(finished: FinishResult?): Boolean {
        if (finished !is FinishResult.ReadyToSend || finished.transcription.text.isBlank() || history == null) return false
        return try {
            history.save(finished.transcription)
            false
        } catch (e: Exception) {
            true
        }
    }

    /** Runs on main. Copies non-blank text (a clipboard that throws is skipped) and answers the line for the outcome. */
    fun line(finished: FinishResult?, saveFailed: Boolean, lines: OutcomeLines): String = when {
        finished is FinishResult.ReadyToSend && finished.transcription.text.isNotBlank() -> {
            try {
                clipboard.copy(finished.transcription.text)
            } catch (e: Exception) {
                // A clipboard that throws must not stop the settle.
            }
            if (saveFailed) lines.copiedNotSaved else lines.savedAndCopied
        }
        finished is FinishResult.ReadyToSend -> lines.blank
        finished is FinishResult.Failed && finished.sentence == EMPTY_TAKE_SENTENCE -> lines.noAudio
        else -> lines.notConverted
    }
}
