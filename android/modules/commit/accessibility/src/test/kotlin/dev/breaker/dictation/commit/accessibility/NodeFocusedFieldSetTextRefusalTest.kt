package dev.breaker.dictation.commit.accessibility

import dev.breaker.dictation.commit.FieldCommit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every way [NodeFocusedField] turns a text away once the field is found and its flags are
 * clean: the insert rules refuse it (too long a result), or the field does not take the
 * text, or the cursor call fails after the text went in. The counters of the node double show
 * how far the insert got: after a refusal by the rules every read was made and nothing was
 * written, and a setText that fails is never followed by a cursor call.
 * The lookup and flag refusals are in `NodeFocusedFieldRefusalTest`. The expected counts and
 * call lists are worked out by hand from the order of the steps.
 */
internal class NodeFocusedFieldSetTextRefusalTest {

    /** A field over a fresh node and finder that write to one shared call list. */
    private class Rig {
        val ownPackage: String = "com.breaker.own"
        val calls: MutableList<String> = ArrayList()
        val node: FakeFieldNode = FakeFieldNode(calls)
        val finder: FakeFocusedNodeFinder = FakeFocusedNodeFinder(node, calls)
        val field: NodeFocusedField = NodeFocusedField(finder, ownPackage)
    }

    /** The expected [FakeFieldNode.counts]: every counter that is not named is 0. */
    private fun counts(
        packageName: Int = 0,
        password: Int = 0,
        editable: Int = 0,
        enabled: Int = 0,
        hint: Int = 0,
        maxLength: Int = 0,
        text: Int = 0,
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
        refresh: Int = 0,
        setText: Int = 0,
        setSelection: Int = 0,
        release: Int = 0,
    ): List<Int> = listOf(
        packageName,
        password,
        editable,
        enabled,
        hint,
        maxLength,
        text,
        selectionStart,
        selectionEnd,
        refresh,
        setText,
        setSelection,
        release,
    )

    /** The counts after a refusal by the insert rules: every read of step 7 was made, nothing was written, the node went back. */
    private fun afterAllReads(): List<Int> = counts(
        packageName = 1,
        password = 1,
        editable = 1,
        enabled = 1,
        hint = 1,
        maxLength = 1,
        text = 1,
        selectionStart = 1,
        selectionEnd = 1,
        refresh = 1,
        release = 1,
    )

    @Test
    fun `a text that would be too long is refused and nothing is written`() {
        // "abc" with the cursor at the end plus "X" is 4 units and the limit is 3.
        val rig = Rig()
        rig.node.holdText("abc")
        rig.node.selectionStart = 3
        rig.node.selectionEnd = 3
        rig.node.maxTextLength = 3

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: a text over the limit must be refused", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: a refused text must leave setText and setSelection alone", afterAllReads(), rig.node.counts())
        assertEquals("commit/accessibility: the node must still be released last", FakeFieldNode.RELEASE, rig.calls.last())
    }

    @Test
    fun `the limit counts the new text and not the dictated text`() {
        // "XY" is 2 units, under the limit of 4, but "abc" + "XY" is 5 units: refused.
        val rig = Rig()
        rig.node.holdText("abc")
        rig.node.selectionStart = 3
        rig.node.selectionEnd = 3
        rig.node.maxTextLength = 4

        val result: FieldCommit = rig.field.commitText("XY")

        assertEquals("commit/accessibility: the merged text is over the limit, so it must be refused", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: a refused text must leave setText and setSelection alone", afterAllReads(), rig.node.counts())
    }

    @Test
    fun `a limit of zero refuses every text`() {
        // "" + "X" is 1 unit and the limit is 0.
        val rig = Rig()
        rig.node.holdText("")
        rig.node.selectionStart = 0
        rig.node.selectionEnd = 0
        rig.node.maxTextLength = 0

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: a limit of zero must refuse every text", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: a refused text must leave setText and setSelection alone", afterAllReads(), rig.node.counts())
    }

    @Test
    fun `a setText that answers false is a refusal and the cursor is not touched`() {
        val rig = Rig()
        rig.node.holdText("abc")
        rig.node.selectionStart = 1
        rig.node.selectionEnd = 1
        rig.node.setTextResult = false

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: a field that did not take the text must be refused", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: setText must be tried once", 1, rig.node.setTextCalls)
        assertEquals("commit/accessibility: no cursor may be placed after a refused setText", 0, rig.node.setSelectionCalls)
        assertEquals("commit/accessibility: the node must still be released last", FakeFieldNode.RELEASE, rig.calls.last())
    }

    @Test
    fun `a setText that throws is a refusal and the cursor is not touched`() {
        val rig = Rig()
        rig.node.holdText("abc")
        rig.node.selectionStart = 1
        rig.node.selectionEnd = 1
        rig.node.setTextFailure = IllegalStateException("setText failed")

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: a setText that throws must be a refusal", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: setText must be tried once", 1, rig.node.setTextCalls)
        assertEquals("commit/accessibility: no cursor may be placed after a setText that threw", 0, rig.node.setSelectionCalls)
        assertEquals("commit/accessibility: the node must still be released last", FakeFieldNode.RELEASE, rig.calls.last())
    }

    @Test
    fun `a setSelection that answers false still leaves the insert accepted`() {
        val rig = Rig()
        rig.node.holdText("abc")
        rig.node.selectionStart = 1
        rig.node.selectionEnd = 1
        rig.node.setSelectionResult = false

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: the text landed, so a cursor that stayed put is not a refusal", FieldCommit.ACCEPTED, result)
        assertEquals("commit/accessibility: setText must have been called once", 1, rig.node.setTextCalls)
        assertEquals("commit/accessibility: setSelection must have been tried once", 1, rig.node.setSelectionCalls)
        assertEquals("commit/accessibility: the node must still be released last", FakeFieldNode.RELEASE, rig.calls.last())
    }

    @Test
    fun `a setSelection that throws still leaves the insert accepted`() {
        val rig = Rig()
        rig.node.holdText("abc")
        rig.node.selectionStart = 1
        rig.node.selectionEnd = 1
        rig.node.setSelectionFailure = IllegalStateException("setSelection failed")

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: the text landed, so a throwing cursor call is not a refusal", FieldCommit.ACCEPTED, result)
        assertEquals("commit/accessibility: setText must have been called once", 1, rig.node.setTextCalls)
        assertEquals("commit/accessibility: setSelection must have been tried once", 1, rig.node.setSelectionCalls)
        assertEquals("commit/accessibility: the node must still be released last", FakeFieldNode.RELEASE, rig.calls.last())
    }
}
