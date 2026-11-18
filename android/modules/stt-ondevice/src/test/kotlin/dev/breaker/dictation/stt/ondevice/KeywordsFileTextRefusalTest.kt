package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check of a keywords file text: each refusal, the number boundaries, and the size caps. */
class KeywordsFileTextRefusalTest {

    private val tokens = setOf("A", "B", "AA")

    private fun refusal(text: String, known: Set<String> = tokens): String? =
        KeywordsFileText.refusal(text, known)

    private fun assertRefusal(label: String, expected: String, text: String, known: Set<String> = tokens) {
        assertEquals("stt-ondevice: $label", expected, refusal(text, known))
    }

    private fun assertValid(label: String, text: String, known: Set<String> = tokens) {
        assertNull("stt-ondevice: $label is valid but got ${refusal(text, known)}", refusal(text, known))
    }

    @Test
    fun `a rendered file of lines with and without numbers is valid`() {
        val lines = listOf(
            KeywordLine(listOf("A", "B")),
            KeywordLine(listOf("A"), boost = 2.5f),
            KeywordLine(listOf("B"), threshold = 0.25f),
            KeywordLine(listOf("A", "B"), boost = 100f, threshold = 1f),
            KeywordLine(listOf("AA"), boost = 0f, threshold = 0f),
            KeywordLine(listOf("A"), boost = 1.0E-4f, threshold = 1.0E-4f),
        )
        assertValid("rendered lines", KeywordsFileText.render(lines))
    }

    @Test
    fun `a space after the colon or the hash sign is refused`() {
        val space = KeywordsFileText.SPACE_AFTER_MARKER
        assertRefusal("space after colon", space, "A : 2\n")
        assertRefusal("space after hash", space, "A # 0.5\n")
        assertRefusal("colon then end of line", space, "A :\n")
        assertRefusal("space after the second marker", space, "A :2 # 0.5\n")
    }

    @Test
    fun `an empty text and an empty line are refused`() {
        val empty = KeywordsFileText.EMPTY_LINE
        assertRefusal("empty text", empty, "")
        assertRefusal("only a newline", empty, "\n")
        assertRefusal("empty line between", empty, "A\n\nB\n")
        assertRefusal("empty first line", empty, "\nA\n")
        assertRefusal("line of only a boost", empty, ":2\n")
    }

    @Test
    fun `a token that the model does not know is refused`() {
        val unknown = KeywordsFileText.UNKNOWN_TOKEN
        assertRefusal("unknown token", unknown, "A Z\n")
        assertRefusal("unknown token on the second line", unknown, "A\nZ\n")
        assertRefusal("unknown token before a boost", unknown, "Z :2\n")
        assertRefusal("empty tokens set", unknown, "A\n", emptySet())
    }

    @Test
    fun `a double space or a trailing space is a malformed line`() {
        val malformed = KeywordsFileText.MALFORMED_LINE
        assertRefusal("double space", malformed, "A  B\n")
        assertRefusal("trailing space", malformed, "A \n")
        assertRefusal("leading space", malformed, " A\n")
    }

    @Test
    fun `the markers must come after the tokens once each and in order`() {
        val malformed = KeywordsFileText.MALFORMED_LINE
        assertValid("boost then threshold", "A :5 #0.5\n")
        assertValid("threshold alone", "A #0.5\n")
        assertRefusal("threshold then boost", malformed, "A #0.5 :5\n")
        assertRefusal("two boosts", malformed, "A :5 :6\n")
        assertRefusal("two thresholds", malformed, "A #0.5 #0.6\n")
        assertRefusal("a token after a marker", malformed, "A :5 B\n")
    }

    @Test
    fun `boost boundary values 0 and 100 are valid and just outside is refused`() {
        val bad = KeywordsFileText.BAD_NUMBER
        for (ok in listOf("0", "0.0", "100", "100.0", "100.000", "50.5", "007")) assertValid("boost $ok", "A :$ok\n")
        for (no in listOf("100.0000001", "100.1", "101", "1000", "-1", "-0.0000001", "NaN", "abc", "1e2", ".5", "5.", "+5")) {
            assertRefusal("boost $no", bad, "A :$no\n")
        }
    }

    @Test
    fun `threshold boundary values 0 and 1 are valid and just outside is refused`() {
        val bad = KeywordsFileText.BAD_NUMBER
        for (ok in listOf("0", "0.0", "1", "1.0", "1.000", "0.5")) assertValid("threshold $ok", "A #$ok\n")
        for (no in listOf("1.0000001", "1.1", "2", "10", "-0.1", "-0.0000001", "NaN", "abc", "1e0", ".5", "5.")) {
            assertRefusal("threshold $no", bad, "A #$no\n")
        }
    }

    @Test
    fun `a bad number after a good boost is still refused`() {
        assertRefusal("bad threshold", KeywordsFileText.BAD_NUMBER, "A :5 #2\n")
        assertRefusal("bad boost with good threshold", KeywordsFileText.BAD_NUMBER, "A :500 #0.5\n")
    }

    @Test
    fun `sixteen lines are valid and seventeen are refused`() {
        assertValid("sixteen lines", "A\n".repeat(16))
        assertValid("sixteen lines without the last newline", "A\n".repeat(15) + "A")
        assertRefusal("seventeen lines", KeywordsFileText.TOO_MANY_LINES, "A\n".repeat(17))
        assertRefusal("seventeen lines without the last newline", KeywordsFileText.TOO_MANY_LINES, "A\n".repeat(16) + "A")
    }

    private fun lineOfBytes255(): String = "A ".repeat(127) + "A"

    @Test
    fun `4096 bytes are valid and 4097 bytes are refused`() {
        val full = (lineOfBytes255() + "\n").repeat(16)
        assertEquals("stt-ondevice: fixture size", 4096, full.toByteArray(Charsets.UTF_8).size)
        assertValid("4096 bytes", full)
        val over = (lineOfBytes255() + "\n").repeat(15) + lineOfBytes255().removeSuffix("A") + "AA\n"
        assertEquals("stt-ondevice: over fixture size", 4097, over.toByteArray(Charsets.UTF_8).size)
        assertRefusal("4097 bytes", KeywordsFileText.TOO_LARGE, over)
    }

    @Test
    fun `the size cap counts bytes and not characters`() {
        val marked = setOf("▁B")
        val line = "▁B ".repeat(85).trimEnd() + "\n"
        val text = line.repeat(16)
        assertTrue("stt-ondevice: characters ${text.length} must be under the cap", text.length <= 4096)
        assertTrue("stt-ondevice: bytes must be over the cap", text.toByteArray(Charsets.UTF_8).size > 4096)
        assertRefusal("many bytes few characters", KeywordsFileText.TOO_LARGE, text, marked)
    }

    @Test
    fun `a text that is too large and too long is refused as too large first`() {
        val text = (lineOfBytes255() + "\n").repeat(17)
        assertRefusal("size before line count", KeywordsFileText.TOO_LARGE, text)
    }
}
