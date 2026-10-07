package dev.breaker.dictation.commit.accessibility

import dev.breaker.dictation.commit.FieldCommit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The paths on which [NodeFocusedField] puts the text in: what the node is given as its new
 * text and as its cursor, how many times each member is touched, and in what order.
 * Every expected text and cursor is worked out by hand from the insert rules, and every
 * count and order comes from the node double, never from the code under test.
 */
internal class NodeFocusedFieldInsertTest {

    /** A field over a fresh node and finder that write to one shared call list. */
    private class Rig {
        val ownPackage: String = "com.breaker.own"
        val calls: MutableList<String> = ArrayList()
        val node: FakeFieldNode = FakeFieldNode(calls)
        val finder: FakeFocusedNodeFinder = FakeFocusedNodeFinder(node, calls)
        val field: NodeFocusedField = NodeFocusedField(finder, ownPackage)

        /** Make the node report [text] with the selection [start] to [end], without counting as reads. */
        fun holds(text: String?, start: Int, end: Int) {
            node.holdText(text)
            node.selectionStart = start
            node.selectionEnd = end
        }
    }

    /** Commit [dictated] and assert it is accepted, with exactly one setText of [expectedText] and one cursor at [expectedCursor]. */
    private fun expectAccepted(label: String, rig: Rig, dictated: String, expectedText: String, expectedCursor: Int) {
        val result: FieldCommit = rig.field.commitText(dictated)
        assertEquals("commit/accessibility: " + label + ": the insert must be accepted", FieldCommit.ACCEPTED, result)
        assertEquals(
            "commit/accessibility: " + label + ": setText must get exactly the merged text, once",
            listOf(expectedText),
            rig.node.setTextArguments,
        )
        assertEquals(
            "commit/accessibility: " + label + ": setSelection must get the cursor as start and end, once",
            listOf(Pair(expectedCursor, expectedCursor)),
            rig.node.setSelectionArguments,
        )
    }

    @Test
    fun `the text goes in at a cursor in the middle at the start and at the end`() {
        // "hello" + "X" + "world"; the cursor is 5 + 1.
        val middle = Rig()
        middle.holds("helloworld", 5, 5)
        expectAccepted("cursor in the middle", middle, "X", "helloXworld", 6)

        // "" + "XY" + "abc"; the cursor is 0 + 2.
        val start = Rig()
        start.holds("abc", 0, 0)
        expectAccepted("cursor at the start", start, "XY", "XYabc", 2)

        // "abc" + "XY" + ""; the cursor is 3 + 2.
        val end = Rig()
        end.holds("abc", 3, 3)
        expectAccepted("cursor at the end", end, "XY", "abcXY", 5)
    }

    @Test
    fun `a selection is replaced by the dictated text`() {
        // Characters 6 to 11 of "hello world" are "world": "hello " + "X" + ""; the cursor is 6 + 1.
        val partial = Rig()
        partial.holds("hello world", 6, 11)
        expectAccepted("partial selection", partial, "X", "hello X", 7)

        // The whole text of three characters goes: "" + "XY" + ""; the cursor is 0 + 2.
        val whole = Rig()
        whole.holds("abc", 0, 3)
        expectAccepted("whole text selected", whole, "XY", "XY", 2)

        // A reversed selection is the same selection: 11 to 6 is 6 to 11.
        val reversed = Rig()
        reversed.holds("hello world", 11, 6)
        expectAccepted("reversed selection", reversed, "X", "hello X", 7)

        // An end beyond the text is cut back to its length of 3: "ab" + "X" + ""; the cursor is 2 + 1.
        val beyond = Rig()
        beyond.holds("abc", 2, 99)
        expectAccepted("selection end beyond the text", beyond, "X", "abX", 3)
    }

    @Test
    fun `a field that reports no selection takes the text at its end`() {
        // No selection at all: "abc" + "X"; the cursor is 3 + 1.
        val none = Rig()
        none.holds("abc", -1, -1)
        expectAccepted("no selection", none, "X", "abcX", 4)

        // One negative end is as good as none: the insert point is the end of the text.
        val oneNegative = Rig()
        oneNegative.holds("abc", 1, -1)
        expectAccepted("only the start valid", oneNegative, "X", "abcX", 4)
    }

    @Test
    fun `a field with no text takes the text as the whole text`() {
        // A null text counts as "": "" + "X" + ""; no selection means the end of "", so the cursor is 0 + 1.
        val nothing = Rig()
        nothing.holds(null, -1, -1)
        expectAccepted("null text", nothing, "X", "X", 1)

        // An empty text with a cursor at 0 gives the same: "" + "Hi" + ""; the cursor is 0 + 2.
        val empty = Rig()
        empty.holds("", 0, 0)
        expectAccepted("empty text", empty, "Hi", "Hi", 2)
    }

    @Test
    fun `a hint is not kept and its reported selection is ignored`() {
        // The text is the hint: the base is "" whatever the selection says; "" + "Hi" + ""; the cursor is 0 + 2.
        val cursor = Rig()
        cursor.holds("Type here", 3, 3)
        cursor.node.isShowingHint = true
        expectAccepted("hint with a cursor", cursor, "Hi", "Hi", 2)

        val selection = Rig()
        selection.holds("Type here", 0, 9)
        selection.node.isShowingHint = true
        expectAccepted("hint with a selection", selection, "Hi", "Hi", 2)
    }

    @Test
    fun `the dictated text goes in exactly as given`() {
        // No space is added: "hello" + "world"; the cursor is 5 + 5.
        val joined = Rig()
        joined.holds("hello", 5, 5)
        expectAccepted("no spacing added", joined, "world", "helloworld", 10)

        // A line break stays: "a" + "x\ny" + "b" (three characters); the cursor is 1 + 3.
        val lines = Rig()
        lines.holds("ab", 1, 1)
        expectAccepted("multi-line text", lines, "x\ny", "ax\nyb", 4)
    }

    /** Like [expectAccepted], and also assert the finder was asked once and the node was given back once. */
    private fun expectAcceptedAndAsked(label: String, rig: Rig, dictated: String, expectedText: String, expectedCursor: Int) {
        expectAccepted(label, rig, dictated, expectedText, expectedCursor)
        assertEquals("commit/accessibility: " + label + ": the finder must be asked once", 1, rig.finder.findCalls)
        assertEquals("commit/accessibility: " + label + ": the node must be given back once", 1, rig.node.releaseCalls)
    }

    @Test
    fun `a dictated text that is only white space is inserted and not turned away`() {
        // One space is not empty: "hello" + " " + "world"; the cursor is 5 + 1.
        val middle = Rig()
        middle.holds("helloworld", 5, 5)
        expectAcceptedAndAsked("one space in the middle", middle, " ", "hello world", 6)

        // One space into an empty field: "" + " " + ""; the cursor is 0 + 1.
        val empty = Rig()
        empty.holds("", 0, 0)
        expectAcceptedAndAsked("one space into an empty field", empty, " ", " ", 1)

        // Two spaces and a line break replace the selection "bc": "a" + "  \n" + "d"; the cursor is 1 + 3.
        val several = Rig()
        several.holds("abcd", 1, 3)
        expectAcceptedAndAsked("white space replacing a selection", several, "  \n", "a  \nd", 4)
    }

    @Test
    fun `white space at the edges of the dictated text and of the field text is kept`() {
        // "a" + " b " + "": the new text ends with a space; the cursor is 1 + 3.
        val trailing = Rig()
        trailing.holds("a", 1, 1)
        expectAcceptedAndAsked("leading and trailing space dictated", trailing, " b ", "a b ", 4)

        // The field text " a " keeps both spaces: " a" + "X" + " "; the cursor is 2 + 1.
        val padded = Rig()
        padded.holds(" a ", 2, 2)
        expectAcceptedAndAsked("padded field text", padded, "X", " aX ", 3)

        // A text that is only spaces keeps its own length: "  " + "X" + ""; the cursor is 2 + 1.
        val spaces = Rig()
        spaces.holds("  ", 2, 2)
        expectAcceptedAndAsked("field text of spaces", spaces, "X", "  X", 3)
    }

    @Test
    fun `a cursor inside a surrogate pair is moved before the pair`() {
        // The text is "a", a pair of two units, "b": 4 units, so offset 2 is between the halves.
        // The cursor moves down to 1: "a" + "X" + pair + "b"; the cursor is 1 + 1.
        val pair: String = String(Character.toChars(0x1F600))
        val rig = Rig()
        rig.holds("a" + pair + "b", 2, 2)
        expectAccepted("cursor between the halves of a pair", rig, "X", "aX" + pair + "b", 2)
    }

    @Test
    fun `a text exactly as long as the limit is accepted`() {
        // "ab" + "c" is 3 units and the limit is 3: it fits, so the limit read from the node reaches the insert.
        val rig = Rig()
        rig.holds("ab", 2, 2)
        rig.node.maxTextLength = 3
        expectAccepted("exactly at the limit", rig, "c", "abc", 3)
    }

    @Test
    fun `an accepted insert touches the members in the fixed order`() {
        val rig = Rig()
        rig.holds("abc", 1, 1)

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: the insert must be accepted", FieldCommit.ACCEPTED, result)
        // Lookup, refresh, package, the three flags in order, the five reads of step 7 in any
        // order, then the two writes, then the release. That is 14 calls.
        val calls: List<String> = rig.calls
        assertEquals("commit/accessibility: an accepted insert must make exactly 14 calls, got " + calls, 14, calls.size)
        assertEquals(
            "commit/accessibility: the lookup, refresh, package and flag reads must come first, in this order",
            listOf(
                FakeFocusedNodeFinder.FIND_INPUT_FOCUS,
                FakeFieldNode.REFRESH,
                FakeFieldNode.READ_PACKAGE_NAME,
                FakeFieldNode.READ_IS_PASSWORD,
                FakeFieldNode.READ_IS_EDITABLE,
                FakeFieldNode.READ_IS_ENABLED,
            ),
            calls.subList(0, 6),
        )
        assertEquals(
            "commit/accessibility: the hint, limit, text and selection reads must all come after the flags and before the writes",
            listOf(
                FakeFieldNode.READ_IS_SHOWING_HINT,
                FakeFieldNode.READ_MAX_TEXT_LENGTH,
                FakeFieldNode.READ_SELECTION_END,
                FakeFieldNode.READ_SELECTION_START,
                FakeFieldNode.READ_TEXT,
            ),
            calls.subList(6, 11).sorted(),
        )
        assertEquals(
            "commit/accessibility: setText, then setSelection, then release must come last, in this order",
            listOf(FakeFieldNode.SET_TEXT, FakeFieldNode.SET_SELECTION, FakeFieldNode.RELEASE),
            calls.subList(11, 14),
        )
    }

    @Test
    fun `an accepted insert makes exactly one call on every member and one lookup`() {
        val rig = Rig()
        rig.holds("abc", 1, 1)

        rig.field.commitText("X")

        assertEquals(
            "commit/accessibility: every node member, the text read included, must be used exactly once",
            List(13) { 1 },
            rig.node.counts(),
        )
        assertEquals("commit/accessibility: the finder must be asked exactly once", 1, rig.finder.findCalls)
    }

    @Test
    fun `each call looks the field up again and reads its state again`() {
        // First: "ab" with the cursor at 2 gives "abX" and the double moves the cursor to 3.
        // Second: the same node now reads "abX" with the cursor at 3, so "Y" gives "abXY" and cursor 4.
        val rig = Rig()
        rig.holds("ab", 2, 2)

        val first: FieldCommit = rig.field.commitText("X")
        val second: FieldCommit = rig.field.commitText("Y")

        assertEquals("commit/accessibility: the first insert must be accepted", FieldCommit.ACCEPTED, first)
        assertEquals("commit/accessibility: the second insert must be accepted", FieldCommit.ACCEPTED, second)
        assertEquals(
            "commit/accessibility: the second insert must be built from the state the node reports then",
            listOf("abX", "abXY"),
            rig.node.setTextArguments,
        )
        assertEquals(
            "commit/accessibility: the second cursor must follow the second text",
            listOf(Pair(3, 3), Pair(4, 4)),
            rig.node.setSelectionArguments,
        )
        assertEquals("commit/accessibility: every call must look the field up", 2, rig.finder.findCalls)
        assertEquals("commit/accessibility: every call must read the text once", 2, rig.node.textReads)
    }
}
