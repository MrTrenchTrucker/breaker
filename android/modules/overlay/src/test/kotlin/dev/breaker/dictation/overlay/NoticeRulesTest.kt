package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the app's sentence is cleaned before the tile shows it: nothing for null or blank, one space
 * for each line break and tab, trimmed, and cut to 80 characters without splitting a surrogate pair.
 */
class NoticeRulesTest {

    private val high = 0xD83D.toChar()
    private val low = 0xDE00.toChar()

    private fun letters(count: Int): String = String(CharArray(count) { 'a' + it % 26 })

    private fun norm(text: String?): String? = NoticeRules.normalise(text)

    /** A failure means the limit is not the 80 characters the tile is laid out for. */
    @Test
    fun `the limit is 80 characters`() {
        assertEquals("overlay: the notice limit expected 80", 80, NoticeRules.MAX_CHARS)
    }

    /** A failure means a null or blank text gives something to draw, or an unusual blank slips through as a space. */
    @Test
    fun `null and blank texts give nothing`() {
        assertNull("overlay: null expected no notice", norm(null))
        listOf(
            "", " ", "   ", "\n", "\t", "\r\n", " \n\t \r ",
            0xA0.toChar().toString(), 0x2028.toChar().toString(), 0x85.toChar().toString(), 0x0B.toChar().toString() + 0x0C.toChar(),
        ).forEach { blank ->
            assertNull("overlay: blank text with ${blank.length} characters, first code ${blank.firstOrNull()?.code}, expected no notice", norm(blank))
        }
    }

    /** A failure means ordinary text is changed, or inside spaces are removed. */
    @Test
    fun `ordinary text is kept as it is`() {
        assertEquals("overlay: plain text expected unchanged", "hello world", norm("hello world"))
        assertEquals("overlay: two inside spaces expected kept", "a  b", norm("a  b"))
        assertEquals("overlay: text with punctuation expected unchanged", "Done. Try again, please!", norm("Done. Try again, please!"))
    }

    /** A failure means a line break or a tab stays in the text, or turns into the wrong number of spaces. */
    @Test
    fun `each line break and tab becomes one space`() {
        assertEquals("overlay: a line feed", "a b", norm("a\nb"))
        assertEquals("overlay: a carriage return", "a b", norm("a\rb"))
        assertEquals("overlay: a tab", "a b", norm("a\tb"))
        assertEquals("overlay: two line feeds", "a  b", norm("a\n\nb"))
        assertEquals("overlay: a carriage return and a line feed", "a  b", norm("a\r\nb"))
        assertEquals("overlay: a line feed and a tab", "a  b", norm("a\n\tb"))
        assertEquals("overlay: a line separator", "a b", norm("a" + 0x2028.toChar() + "b"))
        assertEquals("overlay: a paragraph separator", "a b", norm("a" + 0x2029.toChar() + "b"))
        assertEquals("overlay: a next-line character", "a b", norm("a" + 0x85.toChar() + "b"))
        assertEquals("overlay: a vertical tab and a form feed", "a  b", norm("a" + 0x0B.toChar() + 0x0C.toChar() + "b"))
        val result = norm("one\ntwo\tthree\r\nfour") ?: ""
        assertTrue("overlay: no line break or tab expected to remain in '$result'", result.none { it == '\n' || it == '\r' || it == '\t' })
    }

    /** A failure means leading or trailing white space, or breaks at the ends, survive. */
    @Test
    fun `the text is trimmed`() {
        assertEquals("overlay: spaces at both ends", "hi", norm("  hi  "))
        assertEquals("overlay: breaks and tabs at both ends", "hi", norm("\n\thi\t\r\n"))
        assertEquals("overlay: a leading next-line character", "hi", norm(0x85.toChar() + "hi"))
        assertEquals("overlay: a trailing line separator", "hi", norm("hi" + 0x2028.toChar()))
    }

    /** A failure means a text of exactly 80 characters is shortened, or a text of 81 or more is not cut to its first 80. */
    @Test
    fun `80 characters are kept and 81 are cut to 80`() {
        assertEquals("overlay: 79 characters expected kept", letters(79), norm(letters(79)))
        assertEquals("overlay: 80 characters expected kept", letters(80), norm(letters(80)))
        assertEquals("overlay: 81 characters expected cut to the first 80", letters(80), norm(letters(81)))
        assertEquals("overlay: 200 characters expected cut to the first 80", letters(80), norm(letters(200)))
        assertEquals("overlay: the cut result expected 80 characters long", 80, norm(letters(500))?.length)
    }

    /** A failure means the cut splits a surrogate pair, or drops a whole pair that fits, or keeps a half. */
    @Test
    fun `a surrogate pair is never split by the cut`() {
        val straddling = letters(79) + high + low + "bbb"
        assertEquals("overlay: a pair on characters 80 and 81 expected dropped whole", letters(79), norm(straddling))
        val fitting = letters(78) + high + low + "bbb"
        assertEquals("overlay: a pair ending at character 80 expected kept", letters(78) + high + low, norm(fitting))
        val after = letters(80) + high + low
        assertEquals("overlay: a pair after character 80 expected dropped", letters(80), norm(after))
        val lone = letters(79) + high + "bbb"
        assertEquals("overlay: a lone high half is not a pair and expected kept", letters(79) + high, norm(lone))
        val whole = letters(10) + high + low + letters(10)
        assertEquals("overlay: a short text with a pair expected unchanged", whole, norm(whole))
    }

    /** A failure means white space left at the end by the cut, or after the 80 characters, is kept or counted. */
    @Test
    fun `white space at the cut and after it is dropped`() {
        assertEquals("overlay: 80 characters and then only spaces expected the 80", letters(80), norm(letters(80) + "          "))
        assertEquals("overlay: 80 characters and then only breaks expected the 80", letters(80), norm(letters(80) + "\n\n\t"))
        assertEquals("overlay: a space on character 80 expected trimmed away", letters(79), norm(letters(79) + " bbbb"))
        assertEquals("overlay: a run of spaces over the cut expected trimmed away", letters(70), norm(letters(70) + "                    b"))
        assertEquals("overlay: a break on character 80 expected trimmed away", letters(79), norm(letters(79) + "\nbbbb"))
    }

    /** A failure means some input gives a blank result, a result over the limit, or a result that is altered when cleaned again. */
    @Test
    fun `a result is never blank, never over the limit, and cleaning it again alters nothing`() {
        val samples = listOf(
            "x", " x ", "\nx\n", letters(80), letters(81), letters(300), letters(79) + high + low, "     " + letters(200),
            "a\n" + letters(100), letters(79) + " " + letters(10), letters(79) + "\t" + letters(10), "\n\n\n" + letters(90) + "\n\n",
        )
        samples.forEach { sample ->
            val result = norm(sample)
            assertNotNull("overlay: text starting '${sample.take(5).trim()}' with ${sample.length} characters expected a notice", result)
            assertTrue("overlay: the notice of a ${sample.length} character text expected not blank", result!!.isNotBlank())
            assertTrue("overlay: the notice of a ${sample.length} character text is ${result.length} characters, over 80", result.length <= 80)
            assertEquals("overlay: cleaning the notice of a ${sample.length} character text again expected no change", result, norm(result))
            assertEquals("overlay: the notice expected to have no white space at either end", result, result.trim())
        }
    }
}
