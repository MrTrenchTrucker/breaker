package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three fixed keyword lines: their exact tokens, their order, that they carry
 * no number, and that every token is in a made-up tokens file of the real format.
 * The real tokens file is not in the repository, so no test here checks the lines
 * against the real model.
 */
class FixedPhrasesTest {

    private val mark = KeywordsTestTokens.MARK

    @Test
    fun `the wake line holds the ten tokens of two breakers`() {
        assertEquals(
            "stt-ondevice: wake tokens differ",
            listOf("▁B", "RE", "A", "K", "ER", "▁B", "RE", "A", "K", "ER"),
            FixedPhrases.WAKE.tokens,
        )
    }

    @Test
    fun `the send line holds the apostrophe as its own token`() {
        assertEquals(
            "stt-ondevice: send tokens differ",
            listOf("▁AND", "▁I", "'", "M", "▁GO", "NE"),
            FixedPhrases.SEND.tokens,
        )
    }

    @Test
    fun `the send line without the apostrophe has no apostrophe token`() {
        assertEquals(
            "stt-ondevice: send without apostrophe tokens differ",
            listOf("▁AND", "▁I", "M", "▁GO", "NE"),
            FixedPhrases.SEND_NO_APOSTROPHE.tokens,
        )
    }

    @Test
    fun `the word marker in every token is the one character U+2581`() {
        var marked = 0
        for (line in FixedPhrases.all()) {
            for (token in line.tokens) {
                if (token.startsWith(mark)) {
                    marked++
                    assertEquals("stt-ondevice: marker text of $token", 1, mark.length)
                    assertEquals("stt-ondevice: marker code", 0x2581, token[0].code)
                }
            }
        }
        assertEquals("stt-ondevice: marked tokens counted", 2 + 3 + 3, marked)
    }

    @Test
    fun `all lists wake then send then send without apostrophe in that order`() {
        val all = FixedPhrases.all()
        assertEquals("stt-ondevice: line count", 3, all.size)
        assertSame("stt-ondevice: first is wake", FixedPhrases.WAKE, all[0])
        assertSame("stt-ondevice: second is send", FixedPhrases.SEND, all[1])
        assertSame("stt-ondevice: third is send without apostrophe", FixedPhrases.SEND_NO_APOSTROPHE, all[2])
    }

    @Test
    fun `no fixed line carries a boost or a threshold`() {
        var checked = 0
        for (line in FixedPhrases.all()) {
            assertNull("stt-ondevice: a fixed line has a boost", line.boost)
            assertNull("stt-ondevice: a fixed line has a threshold", line.threshold)
            checked++
        }
        assertEquals(3, checked)
    }

    @Test
    fun `the three fixed lines render to the exact expected text`() {
        val expected = "▁B RE A K ER ▁B RE A K ER\n" +
            "▁AND ▁I ' M ▁GO NE\n" +
            "▁AND ▁I M ▁GO NE\n"
        assertEquals("stt-ondevice: fixed phrases text", expected, KeywordsFileText.render(FixedPhrases.all()))
    }

    @Test
    fun `the tokens fixture holds exactly the distinct tokens of the fixed lines`() {
        val used = FixedPhrases.all().flatMap { it.tokens }.toSet()
        assertEquals("stt-ondevice: distinct token count used", 11, used.size)
        assertEquals("stt-ondevice: fixture tokens", used, KeywordsTestTokens.ALL)
        assertEquals("stt-ondevice: fixture lines", 11, KeywordsTestTokens.FILE_TEXT.trimEnd('\n').split('\n').size)
    }

    @Test
    fun `every token of the fixed lines exists in the made-up tokens file`() {
        var checked = 0
        for (line in FixedPhrases.all()) {
            for (token in line.tokens) {
                assertTrue("stt-ondevice: token $token not in fixture", token in KeywordsTestTokens.ALL)
                checked++
            }
        }
        assertEquals(10 + 6 + 5, checked)
        assertNull(
            "stt-ondevice: rendered fixed text refused with the full set",
            KeywordsFileText.refusal(KeywordsFileText.render(FixedPhrases.all()), KeywordsTestTokens.ALL),
        )
    }

    @Test
    fun `a tokens set missing any one used token is refused`() {
        val text = KeywordsFileText.render(FixedPhrases.all())
        var refused = 0
        for (missing in KeywordsTestTokens.ALL) {
            val message = KeywordsFileText.refusal(text, KeywordsTestTokens.ALL - missing)
            assertNotNull("stt-ondevice: no refusal when $missing is missing", message)
            assertEquals(KeywordsFileText.UNKNOWN_TOKEN, message)
            refused++
        }
        assertEquals("stt-ondevice: tokens tried", 11, refused)
    }

    @Test
    fun `the token file lines split at the last space`() {
        val tokens = KeywordsTestTokens.tokenSet("' 18\n▁B 11\n")
        assertEquals(setOf("'", "▁B"), tokens)
    }
}
