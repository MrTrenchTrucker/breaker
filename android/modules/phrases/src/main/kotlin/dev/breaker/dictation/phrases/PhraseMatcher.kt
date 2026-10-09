package dev.breaker.dictation.phrases

import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.WordUpdate
import java.util.Locale

/**
 * Finds the wake and send phrases inside one recogniser update.
 *
 * An update is the whole hypothesis since the last endpoint, so the matcher reads only
 * the update it is given and keeps no words from earlier updates. Its only state is the
 * set of phrase kinds already reported in the current utterance. That set is cleared
 * after an update marked final, and nothing else clears it.
 */
internal class PhraseMatcher {
    private val reported = mutableSetOf<PhraseKind>()

    /** Events for this update, in the order the phrases appear in it; empty if none (or already reported in this utterance). */
    fun accept(update: WordUpdate): List<PhraseEvent> {
        val tokens = tokenize(update)
        val wakeAt = if (PhraseKind.WAKE in reported) null else findWake(tokens)
        val sendAt = if (PhraseKind.SEND in reported) null else findSend(tokens)

        val events = mutableListOf<PhraseEvent>()
        val wakeFirst = wakeAt != null && (sendAt == null || wakeAt < sendAt)
        if (wakeFirst) events.add(PhraseEvent.Wake)
        if (sendAt != null) events.add(PhraseEvent.Send(tokens[sendAt].startMs))
        if (wakeAt != null && !wakeFirst) events.add(PhraseEvent.Wake)

        if (wakeAt != null) reported.add(PhraseKind.WAKE)
        if (sendAt != null) reported.add(PhraseKind.SEND)
        if (update.final) reported.clear()
        return events
    }
}

/** One normalised word of an update, with the start time of the heard word it came from. */
private class Token(val text: String, val startMs: Long)

private val WHITESPACE = Regex("\\s+")

/** The curly apostrophe, written as an escape so the source stays ASCII. */
private const val CURLY_APOSTROPHE = '\u2019'

/** Index of the first token of the first adjacent "breaker" "breaker" pair, or null. */
private fun findWake(tokens: List<Token>): Int? {
    for (i in 0 until tokens.size - 1) {
        if (tokens[i].text == "breaker" && tokens[i + 1].text == "breaker") return i
    }
    return null
}

/** Index of the "and" token of the first "and" I'm "gone" run (I'm as "i'm", "im", or "i" "m"), or null. */
private fun findSend(tokens: List<Token>): Int? {
    for (i in tokens.indices) {
        if (tokens[i].text != "and") continue
        val afterIm = imEnd(tokens, i + 1) ?: continue
        if (afterIm < tokens.size && tokens[afterIm].text == "gone") return i
    }
    return null
}

/** The index just past "i'm" or "im" (one token) or "i" "m" (two tokens) at [from], or null. */
private fun imEnd(tokens: List<Token>, from: Int): Int? {
    if (from >= tokens.size) return null
    val text = tokens[from].text
    if (text == "i'm" || text == "im") return from + 1
    if (text == "i" && from + 1 < tokens.size && tokens[from + 1].text == "m") return from + 2
    return null
}

/** Splits each heard word on whitespace; every token keeps that heard word's start time. Empty tokens are dropped. */
private fun tokenize(update: WordUpdate): List<Token> {
    val out = mutableListOf<Token>()
    for (word in update.words) {
        for (piece in word.text.split(WHITESPACE)) {
            val text = normalise(piece)
            if (text.isNotEmpty()) out.add(Token(text, word.startMs))
        }
    }
    return out
}

/** Lower-cases with Locale.ROOT, maps the curly apostrophe to an ASCII apostrophe, and keeps only letters, digits and apostrophes. */
private fun normalise(raw: String): String {
    // Redundant: the filter below drops a curly mark anyway. Kept so the intent stays visible.
    val lower = raw.lowercase(Locale.ROOT).replace(CURLY_APOSTROPHE, '\'')
    val sb = StringBuilder(lower.length)
    for (c in lower) {
        if (c.isLetterOrDigit() || c == '\'') sb.append(c)
    }
    return sb.toString()
}
