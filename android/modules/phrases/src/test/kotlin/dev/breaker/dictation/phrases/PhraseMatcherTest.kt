package dev.breaker.dictation.phrases

import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.model.WordUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The matcher's rules, one test per rule. Each expected value is written from the rule,
 * not copied from the matcher's output. The times are chosen so that a send offset can
 * only come from the start time of the word "and".
 */
class PhraseMatcherTest {
    private val wake: PhraseEvent = PhraseEvent.Wake

    private fun breakers(final: Boolean = false): WordUpdate =
        update(final, "breaker" to 0L, "breaker" to 200L)

    @Test
    fun `wake is reported for two adjacent breaker words`() {
        val events = PhraseMatcher().accept(update(false, "breaker" to 0L, "breaker" to 300L))
        assertEquals("two adjacent breaker words are the wake phrase", listOf(wake), events)
    }

    @Test
    fun `wake ignores case and trailing punctuation`() {
        val events = PhraseMatcher().accept(update(false, "BREAKER," to 0L, "Breaker!" to 300L))
        assertEquals("case and punctuation are normalised away", listOf(wake), events)
    }

    @Test
    fun `one breaker word is nothing`() {
        val events = PhraseMatcher().accept(update(false, "breaker" to 0L))
        assertEquals("the wake phrase needs two words", emptyList<PhraseEvent>(), events)
    }

    @Test
    fun `two breaker words with a word between them are nothing`() {
        val events = PhraseMatcher().accept(update(false, "breaker" to 0L, "x" to 100L, "breaker" to 200L))
        assertEquals("the pair must be adjacent", emptyList<PhraseEvent>(), events)
    }

    @Test
    fun `send is reported for I'm written as i'm`() {
        val events = PhraseMatcher().accept(update(false, "and" to 1_000L, "I'm" to 1_200L, "gone" to 1_500L))
        assertEquals("and I'm gone is the send phrase", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `send is reported for I'm written as im`() {
        val events = PhraseMatcher().accept(update(false, "and" to 1_000L, "Im" to 1_200L, "gone" to 1_500L))
        assertEquals("im is the same word as i'm", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `send is reported for I and m as two words`() {
        val events = PhraseMatcher().accept(update(false, "and" to 1_000L, "I" to 1_200L, "m" to 1_300L, "gone" to 1_500L))
        assertEquals("i then m is the same as i'm", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `send is reported for the curly apostrophe form`() {
        val events = PhraseMatcher().accept(update(false, "and" to 1_000L, "I\u2019m" to 1_200L, "gone" to 1_500L))
        assertEquals("the curly apostrophe maps to the plain one", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `send is reported for AND I'M GONE with trailing punctuation`() {
        val events = PhraseMatcher().accept(update(false, "AND" to 1_000L, "I'M" to 1_200L, "GONE." to 1_500L))
        assertEquals("case and the full stop are normalised away", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `send offset is the startMs of and, distinct from the other two words`() {
        val events = PhraseMatcher().accept(update(false, "and" to 1_000L, "i'm" to 1_400L, "gone" to 1_900L))
        assertEquals("the offset is the and word's start, not i'm's or gone's", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `send offset is the startMs of and when other words come before the phrase`() {
        val events = PhraseMatcher().accept(
            update(false, "hello" to 200L, "there" to 600L, "and" to 1_000L, "i'm" to 1_400L, "gone" to 1_900L),
        )
        assertEquals("the offset is the and word's start, not the first word's", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `send offset is zero when and starts the capture`() {
        val events = PhraseMatcher().accept(update(false, "and" to 0L, "i'm" to 300L, "gone" to 600L))
        assertEquals("zero is a valid offset", listOf(PhraseEvent.Send(0L)), events)
    }

    @Test
    fun `send without and is nothing`() {
        val events = PhraseMatcher().accept(update(false, "i'm" to 0L, "gone" to 300L))
        assertEquals("the send phrase starts with and", emptyList<PhraseEvent>(), events)
    }

    @Test
    fun `send with a different third word is nothing`() {
        val events = PhraseMatcher().accept(update(false, "and" to 0L, "i'm" to 300L, "home" to 600L))
        assertEquals("the third word must be gone", emptyList<PhraseEvent>(), events)
    }

    @Test
    fun `a punctuation-only token between breaker words is skipped`() {
        val events = PhraseMatcher().accept(update(false, "breaker" to 0L, "-" to 100L, "breaker" to 200L))
        assertEquals("an empty token neither matches nor breaks adjacency", listOf(wake), events)
    }

    @Test
    fun `a punctuation-only token between send words is skipped`() {
        val events = PhraseMatcher().accept(update(false, "and" to 0L, "..." to 100L, "i'm" to 200L, "gone" to 300L))
        assertEquals("an empty token is skipped in the send phrase too", listOf(PhraseEvent.Send(0L)), events)
    }

    @Test
    fun `a filler word between the send words is not matched`() {
        val events = PhraseMatcher().accept(update(false, "and" to 0L, "uh" to 100L, "i'm" to 200L, "gone" to 300L))
        assertEquals("adjacency is exact, with no filler matching", emptyList<PhraseEvent>(), events)
    }

    @Test
    fun `the same phrase in two partials of one utterance is reported once`() {
        val matcher = PhraseMatcher()
        assertEquals("first partial reports the wake", listOf(wake), matcher.accept(breakers()))
        val second = update(false, "breaker" to 0L, "breaker" to 200L, "hello" to 400L)
        assertEquals("the repeat in a later partial is silent", emptyList<PhraseEvent>(), matcher.accept(second))
    }

    @Test
    fun `the same phrase in a final after partials is not reported again`() {
        val matcher = PhraseMatcher()
        assertEquals("partial reports the wake", listOf(wake), matcher.accept(breakers()))
        assertEquals("the final repeat is silent", emptyList<PhraseEvent>(), matcher.accept(breakers(final = true)))
    }

    @Test
    fun `a phrase first seen in the final update is reported`() {
        val events = PhraseMatcher().accept(breakers(final = true))
        assertEquals("the final is the first sight of the phrase", listOf(wake), events)
    }

    @Test
    fun `after a final the same phrase in the next utterance is reported again`() {
        val matcher = PhraseMatcher()
        assertEquals("the first utterance reports the wake", listOf(wake), matcher.accept(breakers(final = true)))
        assertEquals("the next utterance reports it again", listOf(wake), matcher.accept(breakers()))
    }

    @Test
    fun `dedupe is per kind - wake reported, then send still reported in the same utterance`() {
        val matcher = PhraseMatcher()
        assertEquals("wake first", listOf(wake), matcher.accept(breakers()))
        val both = update(false, "breaker" to 0L, "breaker" to 200L, "and" to 1_000L, "i'm" to 1_200L, "gone" to 1_500L)
        assertEquals("the send is not blocked by the wake", listOf(PhraseEvent.Send(1_000L)), matcher.accept(both))
    }

    @Test
    fun `both phrases in one update come in order of appearance, wake first`() {
        val events = PhraseMatcher().accept(
            update(false, "breaker" to 0L, "breaker" to 200L, "and" to 1_000L, "i'm" to 1_200L, "gone" to 1_500L),
        )
        assertEquals("wake appears first", listOf(wake, PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `both phrases in one update come in order of appearance, send first`() {
        val events = PhraseMatcher().accept(
            update(false, "and" to 0L, "i'm" to 200L, "gone" to 500L, "breaker" to 900L, "breaker" to 1_100L),
        )
        assertEquals("send appears first", listOf(PhraseEvent.Send(0L), wake), events)
    }

    @Test
    fun `a revised hypothesis that no longer holds the phrase reports nothing, and nothing is taken back`() {
        val matcher = PhraseMatcher()
        assertEquals("no gone yet", emptyList<PhraseEvent>(), matcher.accept(update(false, "and" to 0L, "i'm" to 200L)))
        assertEquals("the revision drops the words", emptyList<PhraseEvent>(), matcher.accept(update(false, "hello" to 0L, "there" to 200L)))
        assertEquals(
            "the phrase appears in a later update and is reported then",
            listOf(PhraseEvent.Send(0L)),
            matcher.accept(update(false, "and" to 0L, "i'm" to 200L, "gone" to 400L)),
        )
    }

    @Test
    fun `no carry-over - a breaker word from an earlier update does not pair with one in this update`() {
        val matcher = PhraseMatcher()
        assertEquals("one word is nothing", emptyList<PhraseEvent>(), matcher.accept(update(false, "breaker" to 0L)))
        assertEquals("the earlier word is not kept", emptyList<PhraseEvent>(), matcher.accept(update(false, "breaker" to 200L)))
    }

    @Test
    fun `no carry-over - and and i'm from an earlier update do not join gone in this update`() {
        val matcher = PhraseMatcher()
        assertEquals("no gone yet", emptyList<PhraseEvent>(), matcher.accept(update(false, "and" to 1_000L, "i'm" to 1_200L)))
        assertEquals("gone alone is nothing", emptyList<PhraseEvent>(), matcher.accept(update(false, "gone" to 1_500L)))
    }

    @Test
    fun `an empty update gives an empty list`() {
        assertEquals("no words, no events", emptyList<PhraseEvent>(), PhraseMatcher().accept(update(false)))
    }

    @Test
    fun `an empty final resets the dedupe, so the phrase is reported again`() {
        val matcher = PhraseMatcher()
        assertEquals("wake reported", listOf(wake), matcher.accept(breakers()))
        assertEquals("the empty final is silent", emptyList<PhraseEvent>(), matcher.accept(update(true)))
        assertEquals("after the reset the phrase is reported again", listOf(wake), matcher.accept(breakers()))
    }

    @Test
    fun `a heard word that holds several words gives every token its startMs`() {
        val events = PhraseMatcher().accept(
            WordUpdate(listOf(HeardWord("breaker breaker", 0L), HeardWord("and i'm gone", 1_000L)), false),
        )
        assertEquals("both phrases come from multi-word heard words", listOf(wake, PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `a fresh matcher has no memory of another`() {
        assertEquals("first matcher reports the wake", listOf(wake), PhraseMatcher().accept(breakers()))
        assertEquals("second matcher reports it too", listOf(wake), PhraseMatcher().accept(breakers()))
    }

    @Test
    fun `the same phrase in three partials and a final gives exactly one event in total`() {
        val matcher = PhraseMatcher()
        val events = listOf(
            matcher.accept(breakers()),
            matcher.accept(breakers()),
            matcher.accept(breakers()),
            matcher.accept(breakers(final = true)),
        ).flatten()
        assertEquals("one utterance, one wake", listOf(wake), events)
    }

    @Test
    fun `the same send phrase in three partials and a final gives exactly one event in total`() {
        val matcher = PhraseMatcher()
        val events = listOf(
            matcher.accept(sendUpdate(false)),
            matcher.accept(sendUpdate(false)),
            matcher.accept(sendUpdate(false)),
            matcher.accept(sendUpdate(true)),
        ).flatten()
        assertEquals("one utterance, one send", listOf(PhraseEvent.Send(1_000L)), events)
    }

    @Test
    fun `after a final the same send in the next utterance is reported again`() {
        val matcher = PhraseMatcher()
        assertEquals("the first utterance reports the send", listOf(PhraseEvent.Send(1_000L)), matcher.accept(sendUpdate(true)))
        assertEquals("the next utterance reports it again", listOf(PhraseEvent.Send(1_000L)), matcher.accept(sendUpdate(false)))
    }

    @Test
    fun `dedupe is per kind - send reported, then wake still reported in the same utterance`() {
        val matcher = PhraseMatcher()
        assertEquals("send first", listOf(PhraseEvent.Send(1_000L)), matcher.accept(sendUpdate(false)))
        val both = update(false, "and" to 1_000L, "i'm" to 1_200L, "gone" to 1_500L, "breaker" to 1_800L, "breaker" to 2_000L)
        assertEquals("the wake is not blocked by the send", listOf(wake), matcher.accept(both))
    }

    private fun sendUpdate(final: Boolean): WordUpdate =
        update(final, "and" to 1_000L, "i'm" to 1_200L, "gone" to 1_500L)
}
