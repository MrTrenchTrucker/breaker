package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Test

/** The token table reader: the token is the part before the last space, and only token lines count. */
class KeywordTokenFileTest {

    private val tag = "stt-ondevice keyword tokens:"
    private val mark = "$WORD_MARKER"

    @Test
    fun `a token line gives the part before the last space`() {
        val text = "<blk> 0\n${mark}B 1\n${mark}AND 2\nER 3\n"
        assertEquals(
            "$tag every token of the table is read",
            setOf("<blk>", "${mark}B", "${mark}AND", "ER"),
            KeywordTokenFile.tokensOf(text),
        )
    }

    @Test
    fun `an apostrophe is a token and a token that holds a space is kept whole`() {
        assertEquals(
            "$tag the apostrophe line and the spaced token are read",
            setOf("'", "a b"),
            KeywordTokenFile.tokensOf("' 7\na b 8\n"),
        )
    }

    @Test
    fun `lines that are not token lines are skipped`() {
        val text = "nospace\n\n 5\n  5\ntok abc\ntok \ntok 12x\n   \nreal 9\n"
        assertEquals(
            "$tag only the one real token line is read",
            setOf("real"),
            KeywordTokenFile.tokensOf(text),
        )
    }

    @Test
    fun `carriage return line breaks are accepted`() {
        assertEquals(
            "$tag a table with CRLF breaks reads like one with LF",
            setOf("A", "K"),
            KeywordTokenFile.tokensOf("A 1\r\nK 2\r\n"),
        )
    }

    @Test
    fun `a table with no final line break still gives its last token`() {
        assertEquals(
            "$tag the last line needs no line break",
            setOf("A", "K"),
            KeywordTokenFile.tokensOf("A 1\nK 2"),
        )
    }

    @Test
    fun `an empty table gives no tokens`() {
        assertEquals("$tag empty text has no tokens", emptySet<String>(), KeywordTokenFile.tokensOf(""))
    }
}
