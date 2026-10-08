package dev.breaker.dictation.commit.accessibility

import org.junit.Test

/**
 * How a reported selection is read: put in order, clamped, treated as "none" when negative,
 * ignored for a hint, and kept off the middle of a surrogate pair.
 *
 * Unless a case says otherwise the field text is "abcdef" (6 units, indexes 0 to 5) and the
 * dictated text is "X" (1 unit). Each expected value is worked out by hand from the rules.
 */
internal class InsertPlanSelectionTest {

    @Test
    fun `a reversed selection is put in order`() {
        // (4, 2) is 2 to 4: "ab" + "X" + "ef"; cursor = 2 + 1.
        expectInserted("4 then 2", fieldState("abcdef", 4, 2), "X", "abXef", 3)
        // (6, 0) is the whole text.
        expectInserted("6 then 0", fieldState("abcdef", 6, 0), "X", "X", 1)
        // (1, 0) is 0 to 1: "X" + "bcdef".
        expectInserted("1 then 0", fieldState("abcdef", 1, 0), "X", "Xbcdef", 1)
    }

    @Test
    fun `a selection past the length is clamped to the end`() {
        // (2, 100) is 2 to 6: "ab" + "X".
        expectInserted("end past the length", fieldState("abcdef", 2, 100), "X", "abX", 3)
        // Both past the length: both become 6, an insert point at the end.
        expectInserted("both past the length", fieldState("abcdef", 100, 100), "X", "abcdefX", 7)
        // (50, 2) clamps to (6, 2), then is put in order: 2 to 6.
        expectInserted("start past the length", fieldState("abcdef", 50, 2), "X", "abX", 3)
        // (6, 7) clamps to (6, 6).
        expectInserted("just past the length", fieldState("abcdef", 6, 7), "X", "abcdefX", 7)
    }

    @Test
    fun `a negative start puts the text at the end`() {
        // The valid end (3) is not used: the insert point is the end of the text (6).
        expectInserted("minus 1 then 3", fieldState("abcdef", -1, 3), "X", "abcdefX", 7)
        expectInserted("minus 5 then 3", fieldState("abcdef", -5, 3), "X", "abcdefX", 7)
    }

    @Test
    fun `a negative end puts the text at the end`() {
        expectInserted("3 then minus 1", fieldState("abcdef", 3, -1), "X", "abcdefX", 7)
        // A valid start of 0 is not used either.
        expectInserted("0 then minus 1", fieldState("abcdef", 0, -1), "X", "abcdefX", 7)
    }

    @Test
    fun `both negative means no selection and the text goes at the end`() {
        expectInserted("minus 1 and minus 1", fieldState("abcdef", -1, -1), "X", "abcdefX", 7)
        expectInserted("minus 7 and minus 3", fieldState("abcdef", -7, -3), "X", "abcdefX", 7)
    }

    @Test
    fun `one valid value and one negative never use the valid one`() {
        expectInserted("0 and minus 1", fieldState("abcdef", 0, -1), "X", "abcdefX", 7)
        expectInserted("minus 1 and 0", fieldState("abcdef", -1, 0), "X", "abcdefX", 7)
        expectInserted("2 and minus 1", fieldState("abcdef", 2, -1), "X", "abcdefX", 7)
        expectInserted("minus 1 and 5", fieldState("abcdef", -1, 5), "X", "abcdefX", 7)
    }

    @Test
    fun `the smallest Int is a negative value`() {
        expectInserted("both smallest", fieldState("abcdef", Int.MIN_VALUE, Int.MIN_VALUE), "X", "abcdefX", 7)
        expectInserted("start smallest", fieldState("abcdef", Int.MIN_VALUE, 2), "X", "abcdefX", 7)
        expectInserted("end smallest", fieldState("abcdef", 2, Int.MIN_VALUE), "X", "abcdefX", 7)
        expectInserted("smallest and largest", fieldState("abcdef", Int.MIN_VALUE, Int.MAX_VALUE), "X", "abcdefX", 7)
    }

    @Test
    fun `the largest Int is clamped to the length`() {
        // Both become 6: an insert point at the end.
        expectInserted("both largest", fieldState("abcdef", Int.MAX_VALUE, Int.MAX_VALUE), "X", "abcdefX", 7)
        // 0 to 6 is the whole text.
        expectInserted("0 to largest", fieldState("abcdef", 0, Int.MAX_VALUE), "X", "X", 1)
        // Largest then 0 is put in order: 0 to 6.
        expectInserted("largest to 0", fieldState("abcdef", Int.MAX_VALUE, 0), "X", "X", 1)
        // 2 to 6: "ab" + "X".
        expectInserted("2 to largest", fieldState("abcdef", 2, Int.MAX_VALUE), "X", "abX", 3)
    }

    @Test
    fun `a cursor at zero is the start and not the end`() {
        expectInserted("zero", fieldState("abcdef", 0, 0), "X", "Xabcdef", 1)
        expectInserted("length", fieldState("abcdef", 6, 6), "X", "abcdefX", 7)
        expectInserted("whole text", fieldState("abcdef", 0, 6), "X", "X", 1)
    }

    @Test
    fun `an empty field clamps any selection to nothing`() {
        expectInserted("3 to 5", fieldState("", 3, 5), "X", "X", 1)
        expectInserted("0 to 0", fieldState("", 0, 0), "X", "X", 1)
        expectInserted("both largest", fieldState("", Int.MAX_VALUE, Int.MAX_VALUE), "X", "X", 1)
        expectInserted("smallest and largest", fieldState("", Int.MIN_VALUE, Int.MAX_VALUE), "X", "X", 1)
        expectInserted("5 and minus 1", fieldState("", 5, -1), "X", "X", 1)
    }

    @Test
    fun `a hint is not the text and its reported selection is ignored`() {
        expectInserted("hint with a selection", fieldState("Search here", 3, 5, isShowingHint = true), "X", "X", 1)
        expectInserted("hint with no selection", fieldState("Search here", -1, -1, isShowingHint = true), "X", "X", 1)
        expectInserted(
            "hint with extreme values",
            fieldState("Search here", Int.MAX_VALUE, Int.MIN_VALUE, isShowingHint = true),
            "X",
            "X",
            1,
        )
        expectInserted(
            "hint with the whole range",
            fieldState("Search here", 0, Int.MAX_VALUE, isShowingHint = true),
            "X",
            "X",
            1,
        )
    }

    @Test
    fun `the same text that is not a hint keeps its text around the selection`() {
        // "Search here", positions: S=0 e=1 a=2 r=3 c=4 h=5 and so on. 3 to 5 is "rc": "Sea" + "X" + "h here"; cursor = 3 + 1.
        expectInserted("not a hint", fieldState("Search here", 3, 5), "X", "SeaXh here", 4)
    }

    @Test
    fun `an empty hint gives an empty base`() {
        expectInserted("empty hint", fieldState("", 0, 0, isShowingHint = true), "X", "X", 1)
    }

    @Test
    fun `a hint that holds a pair of surrogates is not kept either`() {
        expectInserted("hint with a pair", fieldState("\uD83D\uDE00", 1, 1, isShowingHint = true), "X", "X", 1)
    }

    // In the surrogate cases the text is "a" + high + low + "b": indexes a0 high1 low2 b3, length 4.
    // Index 2 lies inside the pair (index 1 is a high surrogate and index 2 a low one); 1 and 3 do not.

    @Test
    fun `a cursor inside a pair moves to the start of the pair`() {
        // Cursor 2 moves to 1: "a" + "X" + pair + "b"; cursor = 1 + 1.
        expectInserted("inside", fieldState("a\uD83D\uDE00b", 2, 2), "X", "aX\uD83D\uDE00b", 2)
    }

    @Test
    fun `a cursor on the boundary before a pair stays`() {
        expectInserted("before the pair", fieldState("a\uD83D\uDE00b", 1, 1), "X", "aX\uD83D\uDE00b", 2)
    }

    @Test
    fun `a cursor on the boundary after a pair stays`() {
        // Cursor 3 stays: "a" + pair + "X" + "b"; cursor = 3 + 1.
        expectInserted("after the pair", fieldState("a\uD83D\uDE00b", 3, 3), "X", "a\uD83D\uDE00Xb", 4)
    }

    @Test
    fun `a selection start inside a pair moves down`() {
        // 2 to 3 becomes 1 to 3: the whole pair is replaced, "a" + "X" + "b".
        expectInserted("start inside", fieldState("a\uD83D\uDE00b", 2, 3), "X", "aXb", 2)
    }

    @Test
    fun `a selection end inside a pair moves up`() {
        // 0 to 2 becomes 0 to 3: "X" + "b"; cursor = 0 + 1.
        expectInserted("end inside from the start", fieldState("a\uD83D\uDE00b", 0, 2), "X", "Xb", 1)
        // 1 to 2 becomes 1 to 3: "a" + "X" + "b"; cursor = 1 + 1.
        expectInserted("end inside from the boundary", fieldState("a\uD83D\uDE00b", 1, 2), "X", "aXb", 2)
    }

    @Test
    fun `both ends inside two different pairs are both moved out`() {
        // Text is pair + pair: inside indexes are 1 and 3. 1 to 3 becomes 0 to 4: everything is replaced.
        expectInserted("selection", fieldState("\uD83D\uDE00\uD83D\uDE00", 1, 3), "X", "X", 1)
        // Cursor 3 moves to 2: pair + "X" + pair; cursor = 2 + 1.
        expectInserted(
            "cursor on the second pair",
            fieldState("\uD83D\uDE00\uD83D\uDE00", 3, 3),
            "X",
            "\uD83D\uDE00X\uD83D\uDE00",
            3,
        )
        // Cursor 1 moves to 0: "X" + pair + pair; cursor = 0 + 1.
        expectInserted(
            "cursor on the first pair",
            fieldState("\uD83D\uDE00\uD83D\uDE00", 1, 1),
            "X",
            "X\uD83D\uDE00\uD83D\uDE00",
            1,
        )
    }

    @Test
    fun `a boundary at zero is never moved`() {
        // Text is one pair (length 2); index 0 is not inside anything.
        expectInserted("cursor at 0", fieldState("\uD83D\uDE00", 0, 0), "X", "X\uD83D\uDE00", 1)
        expectInserted("0 to the length", fieldState("\uD83D\uDE00", 0, 2), "X", "X", 1)
        // 0 to 1 has its end inside the pair: it moves up to 2.
        expectInserted("0 to the middle", fieldState("\uD83D\uDE00", 0, 1), "X", "X", 1)
    }

    @Test
    fun `a boundary at the length is never moved`() {
        // Text is one pair (length 2); index 2 equals the length, so it is not inside.
        expectInserted("cursor at the length", fieldState("\uD83D\uDE00", 2, 2), "X", "\uD83D\uDE00X", 3)
        // 1 to 2: the start inside the pair moves to 0, the end at the length stays: everything is replaced.
        expectInserted("middle to the length", fieldState("\uD83D\uDE00", 1, 2), "X", "X", 1)
    }

    @Test
    fun `a reversed selection is put in order before the pair rule is applied`() {
        // (3, 2) is 2 to 3: the start (2) is inside the pair, so it moves down to 1: "a" + "X" + "b".
        expectInserted("reversed start inside", fieldState("a\uD83D\uDE00b", 3, 2), "X", "aXb", 2)
        // (2, 0) is 0 to 2: the end (2) is inside the pair, so it moves up to 3: "X" + "b".
        expectInserted("reversed end inside", fieldState("a\uD83D\uDE00b", 2, 0), "X", "Xb", 1)
    }

    @Test
    fun `a selection is clamped before the pair rule is applied`() {
        // Text is "a" + pair (length 3). 2 to 99 clamps to 2 to 3; the start (2) is inside the pair: 1 to 3.
        expectInserted("clamped end", fieldState("a\uD83D\uDE00", 2, 99), "X", "aX", 2)
    }

    @Test
    fun `a lone high surrogate is not a pair`() {
        // "a" + high + "b": index 2 has a high surrogate before it but "b" after it, so it is not inside a pair.
        expectInserted("high then letter", fieldState("a\uD83Db", 2, 2), "X", "a\uD83DXb", 3)
        // The high surrogate is last: index 2 equals the length.
        expectInserted("high at the end", fieldState("a\uD83D", 2, 2), "X", "a\uD83DX", 3)
        // Two highs: index 1 has a high before it and a high after it.
        expectInserted("two highs", fieldState("\uD83D\uD83D", 1, 1), "X", "\uD83DX\uD83D", 2)
        // 1 to 2 selects the lone high; neither end is inside a pair: "a" + "X" + "b".
        expectInserted("selecting a lone high", fieldState("a\uD83Db", 1, 2), "X", "aXb", 2)
    }

    @Test
    fun `a lone low surrogate is not a pair`() {
        // "a" + low + "b": index 2 has a low surrogate before it, not a high one.
        expectInserted("letter then low", fieldState("a\uDE00b", 2, 2), "X", "a\uDE00Xb", 3)
        // A low surrogate first: index 0 has nothing before it and is never moved.
        expectInserted("low first, cursor at 0", fieldState("\uDE00b", 0, 0), "X", "X\uDE00b", 1)
        // 0 to 1 selects the lone low: "X" + "b".
        expectInserted("low first, selected", fieldState("\uDE00b", 0, 1), "X", "Xb", 1)
    }

    @Test
    fun `a low surrogate followed by a high one is not a pair`() {
        // low + high: index 1 has a low surrogate before it, so the order is wrong for a pair.
        expectInserted("low then high", fieldState("\uDE00\uD83D", 1, 1), "X", "\uDE00X\uD83D", 2)
    }
}
