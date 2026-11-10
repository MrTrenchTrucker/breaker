package dev.breaker.dictation.format

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The guard cases a hunt found: apostrophe look-alikes that hid a negation, an answer of punctuation
// alone for a one word transcript, letters outside the Basic Multilingual Plane, and characters
// that are no text (control characters, a lone surrogate half).
class N9GuardHardeningTest {

    // The same checks for one look-alike apostrophe, written in the middle of "don't".
    private fun assertNegationSurvives(apostrophe: String) {
        val raw = "don${apostrophe}t go"
        assertFalse("android_format: a dropped negation is refused ($apostrophe)", N9Guard.accepts(raw, "go"))
        assertTrue("android_format: the unchanged text is accepted ($apostrophe)", N9Guard.accepts(raw, raw))
        assertTrue(
            "android_format: the plain apostrophe in the answer is accepted ($apostrophe)",
            N9Guard.accepts(raw, "Don't go."),
        )
    }

    @Test
    fun `a left single quotation mark in don't does not hide the negation`() = assertNegationSurvives("‘")

    @Test
    fun `a single high reversed 9 quotation mark in don't does not hide the negation`() = assertNegationSurvives("‛")

    @Test
    fun `a prime in don't does not hide the negation`() = assertNegationSurvives("′")

    @Test
    fun `a modifier letter apostrophe in don't does not hide the negation`() = assertNegationSurvives("ʼ")

    @Test
    fun `an acute accent in don't does not hide the negation`() = assertNegationSurvives("´")

    @Test
    fun `a grave accent in don't does not hide the negation`() = assertNegationSurvives("`")

    @Test
    fun `a fullwidth apostrophe in don't does not hide the negation`() = assertNegationSurvives("＇")

    @Test
    fun `every n't stem is protected with a look-alike apostrophe`() {
        val stems = listOf(
            "doesn", "didn", "haven", "hasn", "hadn", "wouldn", "shouldn", "couldn", "mustn",
            "needn", "weren", "wasn", "aren", "isn", "won", "can", "don",
        )
        for (stem in stems) {
            assertFalse("android_format: ${stem}'t with an acute accent", N9Guard.accepts("${stem}´t stop", "stop"))
        }
    }

    @Test
    fun `a one word transcript is not replaced by punctuation`() {
        assertFalse("android_format: a full stop", N9Guard.accepts("hello", "."))
        assertFalse("android_format: an exclamation mark", N9Guard.accepts("hello", "!"))
        assertFalse("android_format: three dots", N9Guard.accepts("hello", "..."))
        assertFalse("android_format: a two word transcript", N9Guard.accepts("hello world", "."))
        assertTrue("android_format: the word with punctuation", N9Guard.accepts("hello", "Hello."))
    }

    @Test
    fun `a transcript of numbers only still passes with punctuation added`() {
        assertTrue("android_format: unchanged", N9Guard.accepts("123", "123"))
        assertTrue("android_format: punctuation added", N9Guard.accepts("12 34", "12, 34"))
    }

    @Test
    fun `a letter outside the basic multilingual plane is a word`() {
        val mathBoldA = "𝐀"
        val mathBoldB = "𝐁"
        assertFalse("android_format: an added letter", N9Guard.accepts("go now", "go now $mathBoldA"))
        assertFalse("android_format: a changed letter", N9Guard.accepts("go $mathBoldA", "go $mathBoldB"))
        assertTrue("android_format: the same letter kept", N9Guard.accepts("go $mathBoldA", "go $mathBoldA"))
    }

    @Test
    fun `an emoji is not a word so adding one is not caught`() {
        // A symbol is not a letter or a digit, so it counts like punctuation. This pins that known limit.
        assertTrue("android_format: an added emoji", N9Guard.accepts("go now", "go now 😀"))
    }

    @Test
    fun `a control character in the answer is refused`() {
        for (code in listOf(0x00, 0x01, 0x07, 0x0B, 0x0C, 0x1B, 0x7F, 0x85, 0x9F)) {
            val c = code.toChar()
            assertFalse("android_format: U+${code.toString(16)}", N9Guard.accepts("hello world", "hello${c}world"))
        }
        assertFalse("android_format: a control character alone", N9Guard.accepts("hello", "\u0000"))
    }

    @Test
    fun `a tab, a line feed and a carriage return in the answer are accepted`() {
        assertTrue("android_format: tab", N9Guard.accepts("hello world", "hello\tworld"))
        assertTrue("android_format: line feed", N9Guard.accepts("hello world", "hello\nworld"))
        assertTrue("android_format: carriage return, line feed", N9Guard.accepts("hello world", "hello\r\nworld"))
    }

    @Test
    fun `a surrogate half without a partner in the answer is refused`() {
        assertFalse("android_format: a lone high half inside", N9Guard.accepts("hello world", "hello\uD800world"))
        assertFalse("android_format: a lone low half inside", N9Guard.accepts("hello world", "hello\uDC00world"))
        assertFalse("android_format: a lone high half at the end", N9Guard.accepts("hello world", "hello world\uD800"))
        assertFalse("android_format: a reversed pair", N9Guard.accepts("hello world", "hello\uDC00\uD800world"))
    }

    @Test
    fun `word order is not checked so a moved negation is not caught`() {
        // The guard counts words and negations, it does not compare their places. This pins that
        // known limit.
        assertTrue("android_format: a moved word", N9Guard.accepts("do not go", "go not do"))
    }
}
