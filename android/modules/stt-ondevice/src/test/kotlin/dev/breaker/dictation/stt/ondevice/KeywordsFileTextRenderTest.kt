package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The rendering of a keywords file: numbers, the exact text, and every refusal with its boundaries. */
class KeywordsFileTextRenderTest {

    private fun line(boost: Float? = null, threshold: Float? = null) =
        KeywordLine(listOf("A", "B"), boost, threshold)

    private fun message(lines: List<KeywordLine>): String? =
        try {
            KeywordsFileText.render(lines)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }

    private fun assertRefused(label: String, expected: String, lines: List<KeywordLine>) {
        assertEquals("stt-ondevice: $label", expected, message(lines))
    }

    private fun assertRendered(label: String, expected: String, lines: List<KeywordLine>) {
        assertEquals("stt-ondevice: $label", expected, KeywordsFileText.render(lines))
    }

    @Test
    fun `a line with no numbers is its tokens and a newline`() {
        assertRendered("plain", "A B\n", listOf(line()))
    }

    @Test
    fun `a boost renders with a colon and no space after it`() {
        val text = KeywordsFileText.render(listOf(line(boost = 2.5f)))
        assertEquals("stt-ondevice: boost text", "A B :2.5\n", text)
        assertFalse(text, text.contains(": "))
    }

    @Test
    fun `a threshold renders with a hash and no space after it`() {
        val text = KeywordsFileText.render(listOf(line(threshold = 0.25f)))
        assertEquals("stt-ondevice: threshold text", "A B #0.25\n", text)
        assertFalse(text, text.contains("# "))
    }

    @Test
    fun `a boost and a threshold render in that order on one line`() {
        assertRendered("both", "A B :2.5 #0.25\n", listOf(line(2.5f, 0.25f)))
    }

    @Test
    fun `each line ends with a newline and there is one line per keyword`() {
        val text = KeywordsFileText.render(listOf(line(), KeywordLine(listOf("C")), line(1f, 0.5f)))
        assertEquals("stt-ondevice: three lines", "A B\nC\nA B :1.0 #0.5\n", text)
        assertTrue(text.endsWith("\n"))
        assertEquals(3, text.count { it == '\n' })
    }

    @Test
    fun `a comma locale does not change the number text`() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE)) {
                Locale.setDefault(locale)
                assertEquals("control: $locale writes a comma", "2,5", String.format("%.1f", 2.5f))
                assertRendered("boost under $locale", "A B :2.5 #0.25\n", listOf(line(2.5f, 0.25f)))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `a small number is plain decimal text without an exponent`() {
        val text = KeywordsFileText.render(listOf(line(threshold = 1.0E-4f)))
        assertFalse("stt-ondevice: exponent in $text", text.contains("E"))
        assertEquals("stt-ondevice: small threshold", "A B #0.00010\n", text)
        assertNull(KeywordsFileText.refusal(text, setOf("A", "B")))
    }

    @Test
    fun `boost boundary values 0 and 100 are accepted and written plainly`() {
        assertRendered("boost zero", "A B :0.0\n", listOf(line(boost = 0f)))
        assertRendered("boost negative zero", "A B :0.0\n", listOf(line(boost = -0.0f)))
        assertRendered("boost hundred", "A B :100.0\n", listOf(line(boost = 100f)))
    }

    @Test
    fun `boost just outside both ends is refused`() {
        val bad = KeywordsFileText.BAD_NUMBER
        assertRefused("boost above 100", bad, listOf(line(boost = Math.nextUp(100f))))
        assertRefused("boost far above", bad, listOf(line(boost = 101f)))
        assertRefused("boost below 0", bad, listOf(line(boost = -Float.MIN_VALUE)))
        assertRefused("boost minus one", bad, listOf(line(boost = -1f)))
    }

    @Test
    fun `threshold boundary values 0 and 1 are accepted and written plainly`() {
        assertRendered("threshold zero", "A B #0.0\n", listOf(line(threshold = 0f)))
        assertRendered("threshold one", "A B #1.0\n", listOf(line(threshold = 1f)))
    }

    @Test
    fun `threshold just outside both ends is refused`() {
        val bad = KeywordsFileText.BAD_NUMBER
        assertRefused("threshold above 1", bad, listOf(line(threshold = Math.nextUp(1f))))
        assertRefused("threshold two", bad, listOf(line(threshold = 2f)))
        assertRefused("threshold below 0", bad, listOf(line(threshold = -Float.MIN_VALUE)))
    }

    @Test
    fun `a boost or threshold that is NaN or infinite is refused`() {
        val bad = KeywordsFileText.BAD_NUMBER
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertRefused("boost $value", bad, listOf(line(boost = value)))
            assertRefused("threshold $value", bad, listOf(line(threshold = value)))
        }
    }

    @Test
    fun `an empty list is refused`() {
        assertRefused("no lines", KeywordsFileText.NO_LINES, emptyList())
    }

    @Test
    fun `a line with no tokens is refused even after a good line`() {
        assertRefused("no tokens", KeywordsFileText.NO_TOKENS, listOf(KeywordLine(emptyList())))
        assertRefused("second line no tokens", KeywordsFileText.NO_TOKENS, listOf(line(), KeywordLine(emptyList())))
    }

    @Test
    fun `a token that is blank or holds white space is refused`() {
        val bad = KeywordsFileText.BAD_TOKEN
        var tried = 0
        for (token in listOf("", " ", "  ", "A B", "A\tB", "A\nB", " A", "A ")) {
            assertRefused("token '$token'", bad, listOf(KeywordLine(listOf("A", token))))
            tried++
        }
        assertEquals(8, tried)
    }

    @Test
    fun `a token that starts with a colon or a hash sign is refused`() {
        assertRefused("colon token", KeywordsFileText.BAD_TOKEN, listOf(KeywordLine(listOf(":5"))))
        assertRefused("hash token", KeywordsFileText.BAD_TOKEN, listOf(KeywordLine(listOf("#5"))))
        assertRendered("colon inside a token is fine", "A:B\n", listOf(KeywordLine(listOf("A:B"))))
    }

    @Test
    fun `a refused render does not echo a token or a number`() {
        val tokenMessage = message(listOf(KeywordLine(listOf("SECRETWORD LEAK"))))
        val numberMessage = message(listOf(line(boost = 987654f)))
        assertEquals(KeywordsFileText.BAD_TOKEN, tokenMessage)
        assertEquals(KeywordsFileText.BAD_NUMBER, numberMessage)
        assertFalse(tokenMessage, tokenMessage!!.contains("SECRET"))
        assertFalse(numberMessage, numberMessage!!.contains("987654"))
        assertEquals(
            "stt-ondevice: two different bad numbers give one message",
            message(listOf(line(boost = 500f))),
            message(listOf(line(boost = 600f))),
        )
    }
}
