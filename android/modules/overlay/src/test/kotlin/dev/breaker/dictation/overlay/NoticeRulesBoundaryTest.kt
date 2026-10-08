package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the notice rules treat a text that is exactly 80 characters long, with a surrogate half at the
 * end: such a text is already short enough, so it is returned as it is and the cut is never tried.
 */
class NoticeRulesBoundaryTest {

    private val high = 0xD83D.toChar()
    private val low = 0xDE00.toChar()

    private fun letters(count: Int): String = String(CharArray(count) { 'a' + it % 26 })

    /** A failure means the cut logic runs on an 80-character text, reading past its end, or the text is changed. */
    @Test
    fun `a text of exactly 80 characters ending in a lone high surrogate is returned unchanged`() {
        val text = letters(79) + high
        assertEquals("overlay: the test text expected 80 characters", 80, text.length)
        val result = NoticeRules.normalise(text)
        assertEquals("overlay: an 80-character text ending in a lone high surrogate expected unchanged", text, result)
        assertEquals("overlay: the result expected 80 characters", 80, result?.length)
    }

    /**
     * A failure means a text that reaches 80 characters only after trimming is cut or fails, or that a
     * text of 81 characters is cut at the wrong place.
     */
    @Test
    fun `the same text reached after trimming is also returned unchanged`() {
        val text = letters(79) + high
        listOf("   $text", "$text   ", "$text\n\t", "  \n$text \t\n", "   $text\n\t   ").forEach { raw ->
            assertEquals(
                "overlay: raw text with ${raw.length} characters expected to trim to the 80-character text",
                text,
                NoticeRules.normalise(raw),
            )
        }
        val cut = NoticeRules.normalise(letters(79) + high + "b")
        assertEquals(
            "overlay: 81 characters with a high surrogate followed by a plain letter expected cut to 80, keeping the high surrogate",
            letters(79) + high,
            cut,
        )
    }

    /** A failure means an 80-character text ending in the second half of a pair is cut or changed. */
    @Test
    fun `a text of exactly 80 characters ending in a low surrogate is returned unchanged`() {
        val text = letters(79) + low
        assertEquals("overlay: the test text expected 80 characters", 80, text.length)
        assertEquals("overlay: an 80-character text ending in a low surrogate expected unchanged", text, NoticeRules.normalise(text))
    }
}
