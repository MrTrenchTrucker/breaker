package dev.breaker.dictation.commit.accessibility

import org.junit.Test

/**
 * The merge itself: where the dictated text lands, what the new text is, and where the
 * cursor ends up. Every case asserts the whole outcome, new text and cursor together.
 * Each expected value is worked out by hand from the rules, not taken from running the code.
 */
internal class InsertPlanMergeTest {

    @Test
    fun `text goes in the middle at the cursor`() {
        // Cursor after "hello" (5): "hello" + ", big" + " world"; cursor = 5 + 5.
        expectInserted("middle", fieldState("hello world", 5, 5), ", big", "hello, big world", 10)
    }

    @Test
    fun `text goes in at the start`() {
        expectInserted("start", fieldState("world", 0, 0), "hello ", "hello world", 6)
    }

    @Test
    fun `text goes in at the end`() {
        expectInserted("end", fieldState("hello", 5, 5), " world", "hello world", 11)
    }

    @Test
    fun `text goes into an empty field`() {
        expectInserted("empty field with a cursor", fieldState("", 0, 0), "hi", "hi", 2)
        expectInserted("empty field with no selection", fieldState("", -1, -1), "hi", "hi", 2)
    }

    @Test
    fun `a partial selection is replaced`() {
        // Keep "hello " (6 units), drop "world", add "there": cursor = 6 + 5.
        expectInserted("tail selected", fieldState("hello world", 6, 11), "there", "hello there", 11)
        // Drop "hello", add "goodbye" in front of " world": cursor = 0 + 7.
        expectInserted("head selected", fieldState("hello world", 0, 5), "goodbye", "goodbye world", 7)
    }

    @Test
    fun `a selection of the whole text is replaced`() {
        expectInserted("whole text", fieldState("hello", 0, 5), "bye", "bye", 3)
    }

    @Test
    fun `a selection with equal start and end is a cursor`() {
        // Nothing is dropped: "ab" + "X" + "cd".
        expectInserted("cursor at 2", fieldState("abcd", 2, 2), "X", "abXcd", 3)
    }

    @Test
    fun `a one character selection is replaced and is not a cursor`() {
        // Same dictated text as the cursor case above, but "c" (index 2) is dropped: "ab" + "X" + "d".
        expectInserted("one character", fieldState("abcd", 2, 3), "X", "abXd", 3)
    }

    @Test
    fun `no space is added around the dictated text`() {
        expectInserted("glued to the end", fieldState("foo", 3, 3), "bar", "foobar", 6)
        expectInserted("glued inside a gap", fieldState("foo bar", 3, 3), "X", "fooX bar", 4)
    }

    @Test
    fun `spaces in the dictated text are kept as given`() {
        expectInserted("space on both sides", fieldState("foo", 3, 3), " bar ", "foo bar ", 8)
        // Only spaces still count as text: "a" + three spaces + "b"; cursor = 1 + 3.
        expectInserted("only spaces", fieldState("ab", 1, 1), "   ", "a   b", 4)
        // The field ends in a space and the text starts with one: both stay.
        expectInserted("doubled space", fieldState("a ", 2, 2), " b", "a  b", 4)
    }

    @Test
    fun `case and punctuation of the dictated text are kept`() {
        expectInserted("upper case after a capital", fieldState("Hello", 5, 5), "WORLD", "HelloWORLD", 10)
        // "lower." is 6 units, a space is 1, "Upper!" is 6: cursor = 13.
        expectInserted("mixed", fieldState("", 0, 0), "lower. Upper!", "lower. Upper!", 13)
    }

    @Test
    fun `multi-line dictated text goes in whole`() {
        // The dictated text is newline + "line2" + newline + "line3" = 1 + 5 + 1 + 5 = 12 units; cursor = 5 + 12.
        expectInserted(
            "three lines",
            fieldState("line1", 5, 5),
            "\nline2\nline3",
            "line1\nline2\nline3",
            17,
        )
    }

    @Test
    fun `a multi-line field takes text at the cursor on its second line`() {
        // "ab" newline "cd": index 3 is before "c". New text "ab" newline + "X" + "cd"; cursor = 3 + 1.
        expectInserted("second line", fieldState("ab\ncd", 3, 3), "X", "ab\nXcd", 4)
    }

    @Test
    fun `a trailing newline in the dictated text is kept`() {
        expectInserted("trailing newline", fieldState("ab", 2, 2), "x\n", "abx\n", 4)
    }

    @Test
    fun `non-ASCII dictated text counts its UTF-16 units for the cursor`() {
        // "caf" + e with acute accent is 4 units: "a" + 4 units; cursor = 1 + 4.
        expectInserted("accented letter", fieldState("a", 1, 1), "caf\u00e9", "acaf\u00e9", 5)
        // Two CJK characters are 2 units.
        expectInserted("two CJK characters", fieldState("", 0, 0), "\u4f60\u597d", "\u4f60\u597d", 2)
    }

    @Test
    fun `non-ASCII field text is kept around the new text`() {
        // "na" + i with diaeresis + "ve" (5 units); index 3 is after the diaeresis letter: "na\u00ef" + "X" + "ve".
        expectInserted("accent before the cursor", fieldState("na\u00efve", 3, 3), "X", "na\u00efXve", 4)
    }

    @Test
    fun `a dictated pair of surrogates counts two units`() {
        // One emoji is a surrogate pair of 2 units: "a" + pair + "b"; cursor = 1 + 2.
        expectInserted("emoji in the middle", fieldState("ab", 1, 1), "\uD83D\uDE00", "a\uD83D\uDE00b", 3)
        // "x" + pair + "y" is 4 units.
        expectInserted("emoji between letters", fieldState("", 0, 0), "x\uD83D\uDE00y", "x\uD83D\uDE00y", 4)
    }

    @Test
    fun `text after a whole existing pair goes in after it`() {
        // Index 2 is the end of the pair, not inside it: pair + "X" + "b"; cursor = 2 + 1.
        expectInserted("after a pair", fieldState("\uD83D\uDE00b", 2, 2), "X", "\uD83D\uDE00Xb", 3)
    }

    @Test
    fun `special characters in the dictated text go in as given`() {
        // Dollar, 5, space, backslash, space, 1, 0, 0, percent = 9 units.
        expectInserted("dollar and backslash", fieldState("", 0, 0), "\$5 \\ 100%", "\$5 \\ 100%", 9)
        // s a y space quote h i quote space backslash n = 11 units.
        expectInserted("quotes", fieldState("", 0, 0), "say \"hi\" \\n", "say \"hi\" \\n", 11)
    }
}
