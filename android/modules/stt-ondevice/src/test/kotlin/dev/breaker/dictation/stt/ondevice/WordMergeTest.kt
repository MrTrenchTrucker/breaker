package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.HeardWord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Merging sub-word pieces into words, and converting a piece time into milliseconds.
 * Every expected value below is worked out from the rules, not read from the code.
 */
class WordMergeTest {

    private val mark = "$WORD_MARKER"

    @Test
    fun `a single piece that opens with the marker is one word at its own time`() {
        assertEquals(
            "one piece is one word, and its start is its own time",
            listOf(HeardWord("hello", 500L)),
            mergeWords(listOf("${mark}hello"), listOf(0.5f)),
        )
    }

    @Test
    fun `pieces after an opening piece join it and the word keeps the opening time`() {
        assertEquals(
            "three pieces make one word whose start is the first piece's time",
            listOf(HeardWord("hello", 1000L)),
            mergeWords(listOf("${mark}he", "ll", "o"), listOf(1.0f, 1.04f, 1.08f)),
        )
    }

    @Test
    fun `every marker is removed from the text of a word`() {
        assertEquals(
            "markers inside a piece and at its start are removed from the word",
            listOf(HeardWord("xyz", 0L)),
            mergeWords(listOf("${mark}x${mark}y", "z"), listOf(0.0f, 0.1f)),
        )
    }

    @Test
    fun `a first piece without the marker still opens a word and a marker opens the next`() {
        assertEquals(
            "the first piece opens a word, and the marked piece opens the next one",
            listOf(HeardWord("hello", 200L), HeardWord("world", 400L)),
            mergeWords(listOf("he", "llo", "${mark}world"), listOf(0.2f, 0.3f, 0.4f)),
        )
    }

    @Test
    fun `a lone marker piece opens a word that the next plain piece completes`() {
        assertEquals(
            "a marker-only piece has no text, so the plain piece after it is the word, at the marker's time",
            listOf(HeardWord("ab", 500L)),
            mergeWords(listOf(mark, "ab"), listOf(0.5f, 0.6f)),
        )
    }

    @Test
    fun `a word whose text is blank is skipped`() {
        assertEquals(
            "marker-only words and blank words are not reported",
            listOf(HeardWord("ok", 300L)),
            mergeWords(
                listOf(mark, mark, "${mark}ok", mark, "${mark}  "),
                listOf(0.1f, 0.2f, 0.3f, 0.35f, 0.4f),
            ),
        )
    }

    @Test
    fun `an empty first piece is a blank word and is skipped`() {
        assertEquals(
            "an empty piece that opens the list opens a blank word, which is skipped",
            listOf(HeardWord("yes", 100L)),
            mergeWords(listOf("", "${mark}yes"), listOf(0.0f, 0.1f)),
        )
    }

    @Test
    fun `extra pieces without a timestamp are ignored`() {
        assertEquals(
            "only pieces that have a timestamp take part in the merge",
            listOf(HeardWord("a", 0L), HeardWord("b", 500L)),
            mergeWords(listOf("${mark}a", "${mark}b", "${mark}c"), listOf(0.0f, 0.5f)),
        )
        assertEquals(
            "a plain piece with no timestamp is not appended to the word before it",
            listOf(HeardWord("a", 0L)),
            mergeWords(listOf("${mark}a", "b"), listOf(0.0f)),
        )
    }

    @Test
    fun `extra timestamps without a piece are ignored`() {
        assertEquals(
            "a timestamp with no piece opens nothing",
            listOf(HeardWord("a", 0L), HeardWord("b", 500L)),
            mergeWords(listOf("${mark}a", "${mark}b"), listOf(0.0f, 0.5f, 0.75f)),
        )
    }

    @Test
    fun `empty input gives an empty list`() {
        assertEquals("no pieces and no times give no words", emptyList<HeardWord>(), mergeWords(emptyList(), emptyList()))
        assertEquals("pieces without times give no words", emptyList<HeardWord>(), mergeWords(listOf("${mark}a"), emptyList()))
        assertEquals("times without pieces give no words", emptyList<HeardWord>(), mergeWords(emptyList(), listOf(0.5f)))
    }

    @Test
    fun `two words keep the times of their own opening pieces`() {
        assertEquals(
            "the second word starts at its own opening piece",
            listOf(HeardWord("onetwo", 0L), HeardWord("three", 500L)),
            mergeWords(listOf("${mark}one", "two", "${mark}three"), listOf(0.0f, 0.25f, 0.5f)),
        )
    }

    @Test
    fun `a negative timestamp gives a word that starts at zero`() {
        assertEquals(
            "a negative time is clamped to zero, so the word is never negative",
            listOf(HeardWord("neg", 0L)),
            mergeWords(listOf("${mark}neg"), listOf(-1.0f)),
        )
    }

    @Test
    fun `a time of zero seconds is zero milliseconds`() {
        assertEquals("zero seconds is zero samples and zero ms", 0L, startMsOf(0.0f))
    }

    @Test
    fun `a time of twelve hundredths of a second is 120 ms and not the 119 a plain floor gives`() {
        assertEquals(
            "0.12 s is 1920 samples on the 16 kHz grid, and 1920 divided by 16 is 120",
            120L,
            startMsOf(0.12f),
        )
    }

    @Test
    fun `a time of one second is one thousand milliseconds`() {
        assertEquals("one second is 16000 samples, and 16000 divided by 16 is 1000", 1_000L, startMsOf(1.0f))
    }

    @Test
    fun `a time of one thousandth of a second is one millisecond`() {
        assertEquals("0.001 s is 16 samples, and 16 divided by 16 is 1", 1L, startMsOf(0.001f))
    }

    @Test
    fun `a time of one sixteenth of a second floors to sixty two milliseconds`() {
        assertEquals("0.0625 s is 1000 samples, and 1000 divided by 16 floors to 62", 62L, startMsOf(0.0625f))
    }

    @Test
    fun `a negative time converts to zero`() {
        assertEquals("a negative second value is clamped to zero", 0L, startMsOf(-1.0f))
        assertEquals("a tiny negative value gives negative samples and is clamped to zero", 0L, startMsOf(-0.001f))
    }

    @Test
    fun `a large time converts without overflow`() {
        assertEquals("3600 s is 57600000 samples, and that divided by 16 is 3600000", 3_600_000L, startMsOf(3600.0f))
    }
}
