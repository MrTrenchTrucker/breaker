package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import java.util.Locale

/**
 * The on-device recognizer prints some transcripts entirely in capital
 * letters. Such a transcript is normalised at the source: it is turned into
 * lower case, and the standalone personal pronoun gets its capital back:
 * the single letter I and the contractions I'm, I'll, I've, I'd. A
 * transcript that already has any lowercase letter is left exactly as it is,
 * and text with no letters at all passes through. Every case operation uses
 * [Locale.ROOT]. Acronyms are not restored; a word such as NASA comes out
 * lower case.
 */
internal object TranscriptCase {

    private val RESTORED = setOf("i", "i'm", "i'll", "i've", "i'd")

    /**
     * The single-piece rule. When [text] has letters and none of them is
     * lower case, it is lower-cased and the standalone I and the contractions
     * listed above get their capital back; otherwise [text] is returned
     * unchanged.
     */
    fun normaliseText(text: String): String {
        if (text.none { it.isLetter() }) return text
        if (text.any { it.isLowerCase() }) return text
        return restorePersonalI(text.lowercase(Locale.ROOT))
    }

    /**
     * The whole-update rule for the live word stream. The decision runs over
     * the update's words joined with a space: when that joined text has
     * letters and none of them is lower case, every word is normalised by
     * [normaliseText] and a new list is returned; otherwise the words are
     * returned exactly as given.
     */
    fun normaliseWords(words: List<HeardWord>): List<HeardWord> {
        if (words.isEmpty()) return words
        val joined = words.joinToString(" ") { it.text }
        if (joined.none { it.isLetter() }) return words
        if (joined.any { it.isLowerCase() }) return words
        return words.map { HeardWord(normaliseText(it.text), it.startMs) }
    }

    /**
     * Lowers [text] (which must contain no lowercase letter) and restores the
     * capital on a standalone I and on I'm, I'll, I've and I'd. A word is
     * restored only when its whole letter-and-apostrophe body is one of those
     * forms; an I that is merely part of a word (IT, IS, IN, TIME) stays
     * lower case. Surrounding punctuation is kept in place.
     */
    private fun restorePersonalI(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) {
                out.append(text[i])
                i++
            }
            val tokenStart = i
            while (i < text.length && !text[i].isWhitespace()) i++
            out.append(restoredToken(text.substring(tokenStart, i)))
        }
        return out.toString()
    }

    /** Keeps a token's surrounding punctuation in place and restores the body's first letter when the letter-and-apostrophe body is one of the personal forms. */
    private fun restoredToken(token: String): String {
        var start = 0
        while (start < token.length && !isBodyChar(token[start])) start++
        var end = token.length
        while (end > start && !isBodyChar(token[end - 1])) end--
        val body = token.substring(start, end)
        if (body.isEmpty()) return token
        val compared = body.replace('\u2019', '\'')
        if (compared in RESTORED) {
            return token.substring(0, start) + body[0].uppercase(Locale.ROOT) + body.substring(1) + token.substring(end)
        }
        return token
    }

    private fun isBodyChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '\u2019'
}
