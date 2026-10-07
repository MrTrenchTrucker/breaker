package dev.breaker.dictation.commit.accessibility

import org.junit.Test

/**
 * A low surrogate that has no high surrogate in front of it is not half of a pair, so no
 * cursor or selection edge next to it is ever moved. The edge sits exactly before the lone
 * low surrogate, where a check that looks only at the unit after the edge would wrongly see a pair.
 *
 * Offsets are UTF-16 units. In every case the dictated text is "X" (1 unit), and each expected
 * value is worked out by hand from the rules: a position is inside a pair only when the unit
 * before it is a high surrogate and the unit after it is a low one.
 */
internal class InsertPlanSurrogateTest {

    @Test
    fun `a cursor before a lone low surrogate that follows a letter stays`() {
        // "a" + low + "b": a0 low1 b2. Cursor 1: the unit before is "a", not a high surrogate.
        // Insert point stays 1: "a" + "X" + low + "b"; cursor = 1 + 1.
        expectInserted("letter then low", fieldState("a\uDE00b", 1, 1), "X", "aX\uDE00b", 2)
    }

    @Test
    fun `a cursor before a lone low surrogate that follows another low one stays`() {
        // low + low + "b": low0 low1 b2. Cursor 1: the unit before is a low surrogate.
        // Insert point stays 1: low + "X" + low + "b"; cursor = 1 + 1.
        expectInserted("low then low", fieldState("\uDE00\uDE00b", 1, 1), "X", "\uDE00X\uDE00b", 2)
    }

    @Test
    fun `a cursor before a lone low surrogate that follows a high one with a letter between stays`() {
        // high + "a" + low: high0 a1 low2. Cursor 2: the unit before is "a", so no pair.
        // Insert point stays 2: high + "a" + "X" + low; cursor = 2 + 1.
        expectInserted("high letter low", fieldState("\uD83Da\uDE00", 2, 2), "X", "\uD83DaX\uDE00", 3)
    }

    @Test
    fun `a selection end before a lone low surrogate stays`() {
        // "a" + low + "b", selection 0 to 1: the end (1) is not inside a pair, so only "a" goes.
        // "" + "X" + low + "b"; cursor = 0 + 1.
        expectInserted("letter then low, end before", fieldState("a\uDE00b", 0, 1), "X", "X\uDE00b", 1)
        // low + low + "b", selection 0 to 1: the end (1) stays, so only the first low goes.
        // "" + "X" + low + "b"; cursor = 0 + 1.
        expectInserted("low then low, end before", fieldState("\uDE00\uDE00b", 0, 1), "X", "X\uDE00b", 1)
    }

    @Test
    fun `a selection start before a lone low surrogate stays`() {
        // "a" + low + "b", selection 1 to 3: the start (1) stays, so low + "b" go.
        // "a" + "X" + ""; cursor = 1 + 1.
        expectInserted("letter then low, start before", fieldState("a\uDE00b", 1, 3), "X", "aX", 2)
        // low + low + "b", selection 1 to 3: the start (1) stays, so the second low + "b" go.
        // low + "X" + ""; cursor = 1 + 1.
        expectInserted("low then low, start before", fieldState("\uDE00\uDE00b", 1, 3), "X", "\uDE00X", 2)
    }

    @Test
    fun `a reversed selection before a lone low surrogate is put in order and stays`() {
        // (1, 0) on "a" + low + "b" is 0 to 1: the end (1) stays. "X" + low + "b"; cursor = 0 + 1.
        expectInserted("reversed, end before", fieldState("a\uDE00b", 1, 0), "X", "X\uDE00b", 1)
        // (3, 1) on "a" + low + "b" is 1 to 3: the start (1) stays. "a" + "X"; cursor = 1 + 1.
        expectInserted("reversed, start before", fieldState("a\uDE00b", 3, 1), "X", "aX", 2)
    }

    @Test
    fun `a real pair next to a lone low surrogate is still kept whole`() {
        // pair + low: high0 low1 low2. Cursor 1 is inside the pair and moves down to 0.
        // "X" + pair + low; cursor = 0 + 1.
        expectInserted("cursor inside the pair", fieldState("\uD83D\uDE00\uDE00", 1, 1), "X", "X\uD83D\uDE00\uDE00", 1)
        // Cursor 2 is between the pair and the lone low: the unit before it is a low one, so it stays.
        // pair + "X" + low; cursor = 2 + 1.
        expectInserted("cursor after the pair", fieldState("\uD83D\uDE00\uDE00", 2, 2), "X", "\uD83D\uDE00X\uDE00", 3)
    }
}
