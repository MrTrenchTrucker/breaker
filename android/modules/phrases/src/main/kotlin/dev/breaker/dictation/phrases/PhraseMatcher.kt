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

/**
 * Splits each heard word into letter/digit/apostrophe pieces, keeping the owning word's start time.
 *
 * A character that is not a letter, a digit or an ASCII apostrophe ends the current piece without
 * being added (a space, U+00A0, a hyphen, a period or any punctuation acts as a boundary); a piece
 * with no letter or digit is dropped; a hyphen or non-breaking space between letters separates two
 * words instead of merging them.
 */
private fun tokenize(update: WordUpdate): List<Token> {
    val out = mutableListOf<Token>()
    for (word in update.words) {
        var piece = StringBuilder()
        // Map U+2019 -> ' BEFORE the walk so a curly apostrophe is content, never a boundary.
        for (c in word.text.replace('\u2019', '\'').lowercase(Locale.ROOT)) {
            if (c.isLetterOrDigit() || c == '\'') {
                piece.append(c)
            } else {
                // A boundary ends the piece: flush it only if it holds a letter or digit, but always
                // reset it, so a letterless run (a lone apostrophe) never carries into the next letters.
                if (piece.any { it.isLetterOrDigit() }) out.add(Token(piece.toString(), word.startMs))
                piece.setLength(0)
            }
        }
        if (piece.any { it.isLetterOrDigit() }) out.add(Token(piece.toString(), word.startMs))
    }
    return out
}
