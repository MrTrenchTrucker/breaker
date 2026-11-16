package dev.breaker.server.whisper.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Word timestamps are opt in. These tests pin the parser: a verbose reply may carry a words
// array, a word without its three fields is dropped, and no words member means an empty list.
internal class WordTimestampsParseTest {

    private val replyWithWords =
        """{"text":"hello world","segments":[{"start":0.0,"end":1.0,"text":"hello world"}],""" +
            """"words":[{"word":"hello","start":0.0,"end":0.5},{"word":"world","start":0.5,"end":1.0}]}"""

    @Test
    fun `a words array parses into word entries`() {
        val parsed = TranscriptionResponse.parse(replyWithWords)
        assertTrue("whisper-server: the reply must parse: $parsed", parsed != null)
        val words = parsed!!.words
        assertEquals("whisper-server: two words expected", 2, words.size)
        assertEquals("whisper-server: the first word text", "hello", words[0].word)
        assertEquals("whisper-server: the first word start", 0.0, words[0].start, 0.0001)
        assertEquals("whisper-server: the first word end", 0.5, words[0].end, 0.0001)
        assertEquals("whisper-server: the second word text", "world", words[1].word)
        assertEquals("whisper-server: the second word start", 0.5, words[1].start, 0.0001)
        assertEquals("whisper-server: the second word end", 1.0, words[1].end, 0.0001)
    }

    @Test
    fun `no words member leaves the list empty`() {
        val parsed = TranscriptionResponse.parse("""{"text":"hello","language":"en"}""")
        assertTrue("whisper-server: the reply must parse: $parsed", parsed != null)
        assertEquals("whisper-server: no words member means an empty list", 0, parsed!!.words.size)
    }

    @Test
    fun `an empty words array leaves the list empty`() {
        val parsed = TranscriptionResponse.parse("""{"text":"hello","words":[]}""")
        assertTrue("whisper-server: the reply must parse: $parsed", parsed != null)
        assertEquals("whisper-server: an empty words array means an empty list", 0, parsed!!.words.size)
    }

    @Test
    fun `a word object missing an end is skipped`() {
        val parsed = TranscriptionResponse.parse(
            """{"text":"hi","words":[{"word":"a","start":0.0,"end":0.5},{"word":"b","start":0.5}]}"""
        )
        assertTrue("whisper-server: the reply must parse: $parsed", parsed != null)
        val words = parsed!!.words
        assertEquals("whisper-server: only the complete word is kept", 1, words.size)
        assertEquals("whisper-server: the kept word is the complete one", "a", words[0].word)
    }

    @Test
    fun `a word object missing a start is skipped`() {
        val parsed = TranscriptionResponse.parse(
            """{"text":"hi","words":[{"word":"a","end":0.5},{"word":"b","start":0.5,"end":1.0}]}"""
        )
        assertTrue("whisper-server: the reply must parse: $parsed", parsed != null)
        val words = parsed!!.words
        assertEquals("whisper-server: only the complete word is kept", 1, words.size)
        assertEquals("whisper-server: the kept word is the complete one", "b", words[0].word)
    }

    @Test
    fun `a words member that is not an array leaves the list empty`() {
        val parsed = TranscriptionResponse.parse("""{"text":"hello","words":{"word":"hi"}}""")
        assertTrue("whisper-server: the reply must parse: $parsed", parsed != null)
        assertEquals("whisper-server: a non array words member means an empty list", 0, parsed!!.words.size)
    }
}
