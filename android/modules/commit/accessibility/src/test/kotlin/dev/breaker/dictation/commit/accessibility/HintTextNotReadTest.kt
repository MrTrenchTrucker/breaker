package dev.breaker.dictation.commit.accessibility

import dev.breaker.dictation.commit.FieldCommit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A field that is showing its hint must not have its text read: the merge starts from an empty
 * base, so the hint's content is dropped without ever being read.
 */
internal class HintTextNotReadTest {

    /** A fresh node + finder writing to one shared call list; hint state settable. */
    private class Rig {
        val ownPackage: String = "com.breaker.own"
        val calls: MutableList<String> = ArrayList()
        val node: FakeFieldNode = FakeFieldNode(calls)
        val finder: FakeFocusedNodeFinder = FakeFocusedNodeFinder(node, calls)
        val field: NodeFocusedField = NodeFocusedField(finder, ownPackage)
    }

    @Test
    fun `a hint field's text is never read`() {
        val rig = Rig()
        rig.node.holdText("Type here")      // the hint is real screen content
        rig.node.isShowingHint = true
        rig.node.selectionStart = 0
        rig.node.selectionEnd = 9

        val result: FieldCommit = rig.field.commitText("Hi")

        assertEquals("a hint commit is accepted", FieldCommit.ACCEPTED, result)
        // The merge dropped the hint and started from "": setText gets exactly the dictated text.
        assertEquals(
            "the merged text of a hint is just the dictated text",
            listOf("Hi"),
            rig.node.setTextArguments,
        )
        // The read discipline: a hint field's text must never be read.
        assertEquals(
            "a hint field's text is never read",
            0,
            rig.node.textReads,
        )
    }
}
