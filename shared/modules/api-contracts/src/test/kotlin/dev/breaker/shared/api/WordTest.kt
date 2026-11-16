package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The word contract: `Word` carries its three members, and an optional `words`
 * member on TranscriptionResult is absent (null) when the caller does not pass it.
 * The 3-argument call is positional on purpose: it is the call shape that only
 * compiles while `words` stays the LAST constructor member.
 */
class WordTest {

    @Test
    fun `a transcription result built without words answers null for words`() {
        val result = TranscriptionResult("Hello", emptyList(), "en")
        assertNull("api-contracts: words is optional and defaults to none", result.words)
    }

    @Test
    fun `a word carries its text and its two times`() {
        val word = Word(word = "Hello", start = 0.0, end = 1.5)
        assertEquals("Hello", word.word)
        assertEquals(0.0, word.start, 0.0)
        assertEquals(1.5, word.end, 0.0)
    }
}
