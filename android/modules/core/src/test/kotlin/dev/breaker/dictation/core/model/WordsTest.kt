package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words a recogniser reports. A [HeardWord] refuses a blank text and a start
 * before the capture; a [WordUpdate] keeps its own copy of the list it was given
 * and prints counts, never the words.
 */
class WordsTest {
    private val secret = "hunter2-the-words-that-must-stay-private-5c1e"

    /** The message of the refusal [block] must raise; fails if [block] is accepted. */
    private fun refusalMessage(block: () -> Unit): String {
        val refused = try {
            block()
            null
        } catch (refusal: IllegalArgumentException) {
            refusal
        }
        checkNotNull(refused) { "the value was accepted" }
        return checkNotNull(refused.message) { "the refusal carries no message" }
    }

    // -- HeardWord ----------------------------------------------------------------------------

    @Test
    fun `a heard word with a negative start is refused with a message`() {
        listOf(-1L, Long.MIN_VALUE).forEach { start ->
            val message = refusalMessage { HeardWord("word", start) }
            assertTrue("the refusal for $start has no text", message.isNotBlank())
        }
    }

    @Test
    fun `a heard word starting at zero or at the largest value is accepted`() {
        assertEquals(0L, HeardWord("word", 0L).startMs)
        assertEquals(Long.MAX_VALUE, HeardWord("word", Long.MAX_VALUE).startMs)
    }

    @Test
    fun `a heard word with a blank text is refused with a message`() {
        listOf("", " ", "\t\n").forEach { text ->
            val message = refusalMessage { HeardWord(text, 0L) }
            assertTrue("the refusal for '$text' has no text", message.isNotBlank())
        }
    }

    @Test
    fun `a heard word with a non-blank text is accepted`() {
        assertEquals("and", HeardWord("and", 1_200L).text)
    }

    @Test
    fun `heard words are equal by value`() {
        assertEquals(HeardWord("and", 1_200L), HeardWord("and", 1_200L))
        assertNotEquals(HeardWord("and", 1_200L), HeardWord("and", 1_201L))
        assertNotEquals(HeardWord("and", 1_200L), HeardWord("or", 1_200L))
    }

    @Test
    fun `a heard word prints its length and start but not its text`() {
        val printed = HeardWord(secret, 120L).toString()

        assertTrue("it does not name the type: $printed", printed.startsWith("HeardWord("))
        assertFalse("it printed the dictated word: $printed", printed.contains(secret))
        assertTrue("it lacks the length: $printed", printed.contains("${secret.length} chars, startMs=120"))
        assertEquals("it prints more than the length and start: $printed", "HeardWord(${secret.length} chars, startMs=120)", printed)
    }

    // -- WordUpdate ---------------------------------------------------------------------------

    @Test
    fun `an update with an empty hypothesis is allowed`() {
        val update = WordUpdate(emptyList(), false)

        assertTrue(update.words.isEmpty())
        assertFalse(update.final)
    }

    @Test
    fun `an update keeps a copy of the list it was built from`() {
        val source = mutableListOf(HeardWord("one", 0L))
        val update = WordUpdate(source, true)

        source[0] = HeardWord("changed", 0L)
        source.add(HeardWord("two", 10L))

        assertEquals(listOf(HeardWord("one", 0L)), update.words)
    }

    @Test
    fun `the final flag is carried both ways`() {
        assertTrue(WordUpdate(listOf(HeardWord("end", 5L)), true).final)
        assertFalse(WordUpdate(listOf(HeardWord("end", 5L)), false).final)
    }

    @Test
    fun `an update has no public copy that could skip the list copy`() {
        val names = WordUpdate::class.java.methods.map { it.name }
        assertFalse("WordUpdate exposes a public copy: $names", names.any { it == "copy" })
    }

    @Test
    fun `updates are equal by value`() {
        val words = listOf(HeardWord("and", 1_200L))

        assertEquals(WordUpdate(words, true), WordUpdate(words.toList(), true))
        assertNotEquals(WordUpdate(words, true), WordUpdate(words, false))
        assertNotEquals(WordUpdate(words, true), WordUpdate(emptyList(), true))
    }

    @Test
    fun `an update prints its word count and final flag but not the words`() {
        val printed = WordUpdate(listOf(HeardWord(secret, 0L)), true).toString()

        assertTrue("it does not name the type: $printed", printed.startsWith("WordUpdate("))
        assertFalse("it printed the dictated words: $printed", printed.contains(secret))
        assertTrue("it lacks the count and flag: $printed", printed.contains("1 words, final=true"))
    }
}
