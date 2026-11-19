package dev.breaker.dictation.stt.ondevice

import java.io.File
import java.io.IOException

/**
 * Whether a user model's token table can serve a list of keyword lines. It is the check the keyword stream factory
 * makes before it opens the spotter, built from the same three functions: the factory's token cap, the token table
 * reader and the keywords text rules. It writes nothing and opens no engine.
 */
internal object UserKeywordTokens {

    /**
     * True when [tokensFile] is no larger than the factory's token table cap, reads as UTF-8, and holds every token
     * that [phrases] render to. A file that is over the cap or cannot be read is false, and so is a phrase list the
     * keywords renderer refuses. Nothing is written.
     */
    fun fits(tokensFile: File, phrases: List<KeywordLine> = FixedPhrases.all()): Boolean {
        val tokens = readTokens(tokensFile) ?: return false
        val text = try {
            KeywordsFileText.render(phrases)
        } catch (refused: IllegalArgumentException) {
            return false
        }
        return KeywordsFileText.refusal(text, tokens) == null
    }

    /** The tokens of [file], or null when it is over the factory's cap or cannot be read. */
    private fun readTokens(file: File): Set<String>? =
        try {
            if (file.length() > SherpaOnnxKeywordStreamFactory.MAX_TOKENS_BYTES) {
                null
            } else {
                KeywordTokenFile.tokensOf(file.readText(Charsets.UTF_8))
            }
        } catch (failed: IOException) {
            null
        }
}
