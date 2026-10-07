package dev.breaker.dictation.commit.accessibility

import org.junit.Test

/**
 * When the field is left alone, and which reason is given: the four checks on the field and
 * the dictated text in their fixed order, and the field's own length limit.
 * Each expected value is worked out by hand from the rules.
 */
internal class InsertPlanRefusalTest {

    @Test
    fun `a password field is refused`() {
        expectRefused("password", fieldState("abc", 3, 3, isPassword = true), "X", Refusal.PASSWORD)
    }

    @Test
    fun `a password field is refused even when the dictated text is empty`() {
        expectRefused("password with nothing to say", fieldState("abc", 3, 3, isPassword = true), "", Refusal.PASSWORD)
    }

    @Test
    fun `a password field that is also not editable and not enabled is refused as a password`() {
        expectRefused(
            "password, not editable, not enabled",
            fieldState("abc", 3, 3, isPassword = true, isEditable = false, isEnabled = false),
            "X",
            Refusal.PASSWORD,
        )
    }

    @Test
    fun `a field that is not editable is refused`() {
        expectRefused("not editable", fieldState("abc", 3, 3, isEditable = false), "X", Refusal.NOT_EDITABLE)
    }

    @Test
    fun `a field that is not enabled is refused`() {
        expectRefused("not enabled", fieldState("abc", 3, 3, isEnabled = false), "X", Refusal.NOT_ENABLED)
    }

    @Test
    fun `empty dictated text is refused`() {
        expectRefused("empty into text", fieldState("abc", 3, 3), "", Refusal.EMPTY_TEXT)
        expectRefused("empty into an empty field", fieldState("", 0, 0), "", Refusal.EMPTY_TEXT)
    }

    @Test
    fun `only the empty string is empty`() {
        // A single space is text: "a" + " " + "b"; cursor = 1 + 1.
        expectInserted("one space", fieldState("ab", 1, 1), " ", "a b", 2)
        expectInserted("three spaces", fieldState("ab", 2, 2), "   ", "ab   ", 5)
        expectInserted("a newline", fieldState("ab", 0, 0), "\n", "\nab", 1)
        expectInserted("a tab", fieldState("", 0, 0), "\t", "\t", 1)
    }

    @Test
    fun `the refusal order is password then not editable then not enabled then empty`() {
        // Each case fails every check from its own up, so only the order decides the reason.
        expectRefused(
            "password beats not editable",
            fieldState("abc", 0, 0, isPassword = true, isEditable = false),
            "X",
            Refusal.PASSWORD,
        )
        expectRefused(
            "password beats not enabled",
            fieldState("abc", 0, 0, isPassword = true, isEnabled = false),
            "X",
            Refusal.PASSWORD,
        )
        expectRefused(
            "not editable beats not enabled",
            fieldState("abc", 0, 0, isEditable = false, isEnabled = false),
            "X",
            Refusal.NOT_EDITABLE,
        )
        expectRefused(
            "not editable beats empty text",
            fieldState("abc", 0, 0, isEditable = false),
            "",
            Refusal.NOT_EDITABLE,
        )
        expectRefused(
            "not enabled beats empty text",
            fieldState("abc", 0, 0, isEnabled = false),
            "",
            Refusal.NOT_ENABLED,
        )
        expectRefused("empty text alone", fieldState("abc", 0, 0), "", Refusal.EMPTY_TEXT)
    }

    @Test
    fun `a new text exactly at the limit is accepted`() {
        // "abc" + "def" is 6 units and the limit is 6.
        expectInserted("at the limit", fieldState("abc", 3, 3, maxTextLength = 6), "def", "abcdef", 6)
    }

    @Test
    fun `a new text one over the limit is refused`() {
        expectRefused("one over", fieldState("abc", 3, 3, maxTextLength = 5), "def", Refusal.TOO_LONG)
    }

    @Test
    fun `a limit of zero refuses every insert`() {
        expectRefused("zero into an empty field", fieldState("", 0, 0, maxTextLength = 0), "a", Refusal.TOO_LONG)
        expectRefused("zero over a selection", fieldState("abc", 0, 3, maxTextLength = 0), "x", Refusal.TOO_LONG)
        expectRefused("zero with a space", fieldState("", 0, 0, maxTextLength = 0), " ", Refusal.TOO_LONG)
    }

    @Test
    fun `a negative limit means no limit`() {
        val thousandXs: String = "x".repeat(1000)
        expectInserted("minus 1", fieldState("", 0, 0, maxTextLength = -1), thousandXs, thousandXs, 1000)
        expectInserted("minus 5", fieldState("", 0, 0, maxTextLength = -5), thousandXs, thousandXs, 1000)
        expectInserted("smallest Int", fieldState("", 0, 0, maxTextLength = Int.MIN_VALUE), thousandXs, thousandXs, 1000)
    }

    @Test
    fun `the largest limit accepts a normal insert`() {
        expectInserted("largest Int", fieldState("abc", 3, 3, maxTextLength = Int.MAX_VALUE), "d", "abcd", 4)
    }

    @Test
    fun `the limit counts the new text and not the dictated text`() {
        // "abcd" + "xy" is 6 units; the dictated text alone (2) would fit under 5, the new text does not.
        expectRefused("short text, long result", fieldState("abcd", 4, 4, maxTextLength = 5), "xy", Refusal.TOO_LONG)
    }

    @Test
    fun `a replacement that shrinks the field fits under the limit`() {
        // The old text is 6 units, over the limit of 3, but the whole of it is replaced by 2 units.
        expectInserted("shrinking", fieldState("abcdef", 0, 6, maxTextLength = 3), "xy", "xy", 2)
    }

    @Test
    fun `a field already over its limit refuses a longer result`() {
        expectRefused("still over", fieldState("abcdef", 6, 6, maxTextLength = 3), "x", Refusal.TOO_LONG)
    }

    @Test
    fun `a hint is not counted against the limit`() {
        // The hint is 21 units, the limit is 3: only the new text ("abc", 3 units) counts.
        expectInserted(
            "hint and a fitting text",
            fieldState("a very long hint text", 0, 0, isShowingHint = true, maxTextLength = 3),
            "abc",
            "abc",
            3,
        )
        expectRefused(
            "hint and a long text",
            fieldState("a very long hint text", 0, 0, isShowingHint = true, maxTextLength = 2),
            "abc",
            Refusal.TOO_LONG,
        )
    }

    @Test
    fun `a surrogate pair counts as two units against the limit`() {
        expectRefused("pair over 1", fieldState("", 0, 0, maxTextLength = 1), "\uD83D\uDE00", Refusal.TOO_LONG)
        expectInserted("pair at 2", fieldState("", 0, 0, maxTextLength = 2), "\uD83D\uDE00", "\uD83D\uDE00", 2)
    }

    @Test
    fun `a refused insert is never cut short`() {
        // "ab" + "cdef" is 6 units over a limit of 3; the answer is a refusal, not "abc".
        expectRefused("no truncation", fieldState("ab", 2, 2, maxTextLength = 3), "cdef", Refusal.TOO_LONG)
    }

    @Test
    fun `the limit never outranks an earlier refusal`() {
        expectRefused("empty text before the limit", fieldState("", 0, 0, maxTextLength = 0), "", Refusal.EMPTY_TEXT)
        expectRefused(
            "password before the limit",
            fieldState("abc", 0, 0, isPassword = true, maxTextLength = 0),
            "X",
            Refusal.PASSWORD,
        )
        expectRefused(
            "not editable before the limit",
            fieldState("abc", 0, 0, isEditable = false, maxTextLength = 0),
            "X",
            Refusal.NOT_EDITABLE,
        )
        expectRefused(
            "not enabled before the limit",
            fieldState("abc", 0, 0, isEnabled = false, maxTextLength = 0),
            "X",
            Refusal.NOT_ENABLED,
        )
    }
}
