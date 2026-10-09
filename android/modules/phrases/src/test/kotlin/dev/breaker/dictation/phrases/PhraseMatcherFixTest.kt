package dev.breaker.dictation.phrases

import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.model.WordUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the piece-splitting rule in the matcher's tokeniser.
 *
 * A character that is not a letter, a digit or an ASCII apostrophe ends the current piece; a piece
 * with no letter or digit is skipped; every piece carries the start time of the heard word it came
 * from. These tests pin that rule, including the cases where a letterless run sits between letters.
 */
class PhraseMatcherFixTest {

    @Test
    fun `a standalone apostrophe between breaker words is skipped, not treated as content`() {
        // A lone apostrophe holds no letter or digit, so it is skipped; the two "breaker" words
        // stay adjacent.
        val events = PhraseMatcher().accept(update(false, "breaker" to 0L, "'" to 100L, "breaker" to 200L))
        assertEquals("the apostrophe is skipped, the wake still matches", listOf(PhraseEvent.Wake), events)
    }

    @Test
    fun `a non-breaking space is not treated as a word boundary for the wake`() {
        // A non-breaking space ends a piece like a space, so one heard word holding two "breaker"
        // words still wakes.
        val events = PhraseMatcher().accept(update(false, "breaker\u00A0breaker" to 0L))
        assertEquals("the non-breaking space separates the two wake words", listOf(PhraseEvent.Wake), events)
    }

    @Test
    fun `an internal hyphen does not merge two words into a matched send`() {
        // A hyphen ends a piece, so "a-nd" yields "a" and "nd", never the token "and"; no send
        // matches.
        val events = PhraseMatcher().accept(update(false, "a-nd" to 0L, "i'm" to 100L, "gone" to 200L))
        assertEquals("a hyphen separates the pieces; there is no matched send", emptyList<PhraseEvent>(), events)
    }

    @Test
    fun `a U+2019 apostrophe inside a word matches the ASCII form`() {
        // The curly apostrophe is content (mapped before the split), so "I\u2019m" is one piece
        // "i'm" and matches just like the plain form.
        val events = PhraseMatcher().accept(update(false, "and" to 1_000L, "I\u2019m" to 1_200L, "gone" to 1_500L))
        assertEquals("the curly apostrophe is content, so it matches like ASCII", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `a U+2019 between two letters stays a single word and does not match`() {
        // A curly apostrophe between i and m is content, not a boundary: "i\u2019" stays one piece
        // "i'", which is not "i'm", "im", or "i" followed by "m", so no send.
        val events = PhraseMatcher().accept(update(false, "and" to 0L, "i\u2019" to 100L, "m" to 200L, "gone" to 300L))
        assertEquals("a curly apostrophe between i and m is content, so no send", emptyList<PhraseEvent>(), events)
    }

    @Test
    fun `a split piece keeps the start of the heard word it came from`() {
        // Each split piece carries the start of the heard word it came from; the send offset is
        // that start, not a stamped or neighbouring value.
        val events = PhraseMatcher().accept(
            WordUpdate(listOf(HeardWord("and", 1_000L), HeardWord("i'm", 1_200L), HeardWord("gone", 1_500L)), false),
        )
        assertEquals(
            "the send offset is the owning heard word's start",
            listOf(PhraseEvent.Send(1_000L)),
            events,
        )
    }

    @Test
    fun `each piece of a multi-word heard word keeps that heard word's start`() {
        // Every token carries its own heard word's start, not a stamped or neighbouring value.
        val events = PhraseMatcher().accept(
            WordUpdate(
                listOf(
                    HeardWord("and", 100L),
                    HeardWord("i'm", 50L),
                    HeardWord("gone", 900L),
                ),
                false,
            ),
        )
        assertEquals("every token carries its own heard word's start", listOf(PhraseEvent.Send(100L)), events)
    }

    @Test
    fun `a letterless run at the start of a heard word does not carry into the following letters`() {
        // A lone apostrophe at the start of a heard word is skipped; it must not glue to the
        // letters that follow it inside the same heard word.
        val events = PhraseMatcher().accept(
            WordUpdate(listOf(HeardWord("' breaker", 0L), HeardWord("breaker", 200L)), false),
        )
        assertEquals("the leading apostrophe is skipped, the wake still matches", listOf(PhraseEvent.Wake), events)
    }

    @Test
    fun `a letterless run in the middle of a heard word does not carry into a later word`() {
        // A lone apostrophe between two words inside one heard word is skipped; the space after it
        // must not let it glue to the next word.
        val events = PhraseMatcher().accept(
            WordUpdate(listOf(HeardWord("breaker -' breaker", 0L)), false),
        )
        assertEquals("the middle apostrophe is skipped, the wake still matches", listOf(PhraseEvent.Wake), events)
    }

    @Test
    fun `a hyphen between the two wake words in one heard word is a boundary`() {
        // A hyphen ends a piece like a space, so one heard word holding "breaker-breaker" still
        // yields two adjacent "breaker" tokens.
        val events = PhraseMatcher().accept(
            WordUpdate(listOf(HeardWord("breaker-breaker", 0L)), false),
        )
        assertEquals("the hyphen separates the two wake words", listOf(PhraseEvent.Wake), events)
    }

    @Test
    fun `a hyphenated i-m as one heard word matches like the spaced form`() {
        // A hyphen ends a piece, so "i-m" reads as "i" then "m", the same as the two spaced
        // words; the send is reported the way the spaced form reports it.
        val events = PhraseMatcher().accept(
            update(false, "and" to 1_000L, "i-m" to 1_200L, "gone" to 1_500L),
        )
        assertEquals("a hyphenated i-m matches like the spaced form", listOf(PhraseEvent.Send(1_000L)), events)
    }
}
