package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Normalising a transcript the engine prints entirely in capital letters, at
 * the source. Every expected value below is worked out from the rule, not
 * read from the code.
 */
class TranscriptCaseTest {

    @Test
    fun `an all-caps sentence is lower-cased`() {
        assertEquals("all caps come out lower case",
            "hello world how are you",
            TranscriptCase.normaliseText("HELLO WORLD HOW ARE YOU"))
    }

    @Test
    fun `the standalone I keeps its capital in an all-caps sentence`() {
        assertEquals("the standalone I is restored",
            "I am going home now",
            TranscriptCase.normaliseText("I AM GOING HOME NOW"))
    }

    @Test
    fun `the contractions keep a capital I`() {
        assertEquals("each contraction keeps its capital I",
            "I'm going, I'll wait, I've seen it, I'd rather not",
            TranscriptCase.normaliseText("I'M GOING, I'LL WAIT, I'VE SEEN IT, I'D RATHER NOT"))
    }

    @Test
    fun `a word that contains I but is not the pronoun is lower-cased`() {
        assertEquals("an I inside a word stays lower case",
            "it is in time for the trip",
            TranscriptCase.normaliseText("IT IS IN TIME FOR THE TRIP"))
    }

    @Test
    fun `mixed-case text is left exactly as it is`() {
        assertEquals("a model that cases its own text is untouched",
            "This is already Mixed Case",
            TranscriptCase.normaliseText("This is already Mixed Case"))
    }

    @Test
    fun `lowercase text is left exactly as it is`() {
        assertEquals("text that already has a lowercase letter is untouched",
            "already in lower case",
            TranscriptCase.normaliseText("already in lower case"))
    }

    @Test
    fun `empty text is unchanged`() {
        assertEquals("the empty transcript passes through", "", TranscriptCase.normaliseText(""))
    }

    @Test
    fun `digits and punctuation with no letters are unchanged`() {
        assertEquals("text with no letters passes through",
            "123 456 - 789",
            TranscriptCase.normaliseText("123 456 - 789"))
    }

    @Test
    fun `a single letter transcript is lower-cased`() {
        assertEquals("a lone capital letter comes out lower case", "a", TranscriptCase.normaliseText("A"))
    }

    @Test
    fun `a single capital I stays a capital I`() {
        assertEquals("a lone I keeps its capital", "I", TranscriptCase.normaliseText("I"))
    }

    @Test
    fun `an all-I transcript keeps every capital I`() {
        assertEquals("each standalone I in an all-I transcript keeps its capital",
            "I I I", TranscriptCase.normaliseText("I I I"))
    }

    @Test
    fun `the whole live update is decided over the joined words`() {
        assertEquals("an all-caps update normalises every word",
            listOf(HeardWord("hello", 0L), HeardWord("world", 500L)),
            TranscriptCase.normaliseWords(listOf(HeardWord("HELLO", 0L), HeardWord("WORLD", 500L))))
    }

    @Test
    fun `a mixed-case update keeps its uppercase acronym word`() {
        assertEquals("an update with any lowercase word is left exactly as is, acronym included",
            listOf(HeardWord("hello", 0L), HeardWord("NASA", 200L), HeardWord("world", 400L)),
            TranscriptCase.normaliseWords(listOf(HeardWord("hello", 0L), HeardWord("NASA", 200L), HeardWord("world", 400L))))
    }

    @Test
    fun `an update with no letters passes through`() {
        assertEquals("an all-digits update passes through",
            listOf(HeardWord("123", 0L), HeardWord("456", 300L)),
            TranscriptCase.normaliseWords(listOf(HeardWord("123", 0L), HeardWord("456", 300L))))
    }

    @Test
    fun `a curly apostrophe contraction is restored and kept`() {
        assertEquals("the curly apostrophe is recognised and kept in place",
            "I\u2019m going, I\u2019ll wait, I\u2019ve seen it, I\u2019d rather not",
            TranscriptCase.normaliseText("I\u2019M GOING, I\u2019LL WAIT, I\u2019VE SEEN IT, I\u2019D RATHER NOT"))
    }

    @Test
    fun `the word stream restores a curly apostrophe contraction`() {
        assertEquals("an all-caps update with a curly apostrophe normalises the word",
            listOf(HeardWord("I\u2019m", 0L), HeardWord("world", 300L)),
            TranscriptCase.normaliseWords(listOf(HeardWord("I\u2019M", 0L), HeardWord("WORLD", 300L))))
    }

    @Test
    fun `lower and upper casing use the root locale under a Turkish default`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("tr", "TR"))
        try {
            assertEquals("a Turkish default must not dot or dot the i in a title",
                "title I'm here",
                TranscriptCase.normaliseText("TITLE I'M HERE"))
            assertEquals("the word stream uses the root locale under a Turkish default",
                listOf(HeardWord("title", 0L), HeardWord("I'm", 100L), HeardWord("here", 200L)),
                TranscriptCase.normaliseWords(listOf(HeardWord("TITLE", 0L), HeardWord("I'M", 100L), HeardWord("HERE", 200L))))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
