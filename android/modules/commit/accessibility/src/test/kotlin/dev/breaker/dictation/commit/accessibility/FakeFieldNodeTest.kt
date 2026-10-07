package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node double has to count honestly: a later test that says "this was read zero times"
 * is worth nothing if the counter behind it cannot move. Each counter is shown going from
 * 0 to 1 on exactly one call, with every other counter left at 0, and the ordered call
 * list is shown to record every call in the order it was made. The failure switches and the
 * finder are in FakeFieldNodeSwitchesTest.
 */
internal class FakeFieldNodeTest {

    /** The expected [FakeFieldNode.counts]: 1 at [index] and 0 everywhere else. */
    private fun onlyAt(index: Int): List<Int> = List(13) { position -> if (position == index) 1 else 0 }

    @Test
    fun `a new node has every counter at zero and an empty call list`() {
        val node = FakeFieldNode()

        assertEquals("commit/accessibility: a new node must have every counter at zero", List(13) { 0 }, node.counts())
        assertTrue("commit/accessibility: a new node must have an empty call list", node.calls.isEmpty())
        assertTrue("commit/accessibility: a new node must have no setText arguments", node.setTextArguments.isEmpty())
        assertTrue("commit/accessibility: a new node must have no setSelection arguments", node.setSelectionArguments.isEmpty())
    }

    @Test
    fun `reading the package name moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.packageName = "com.example.other"

        val value: String? = node.packageName

        assertEquals("commit/accessibility: the package name read must return what was set", "com.example.other", value)
        assertEquals("commit/accessibility: only the package name counter may move", onlyAt(0), node.counts())
        assertEquals(
            "commit/accessibility: the call list must name the package name read",
            listOf(FakeFieldNode.READ_PACKAGE_NAME),
            node.calls,
        )
    }

    @Test
    fun `reading isPassword moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.isPassword = true

        val value: Boolean = node.isPassword

        assertTrue("commit/accessibility: the isPassword read must return what was set", value)
        assertEquals("commit/accessibility: only the isPassword counter may move", onlyAt(1), node.counts())
        assertEquals(
            "commit/accessibility: the call list must name the isPassword read",
            listOf(FakeFieldNode.READ_IS_PASSWORD),
            node.calls,
        )
    }

    @Test
    fun `reading isEditable moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.isEditable = false

        val value: Boolean = node.isEditable

        assertFalse("commit/accessibility: the isEditable read must return what was set", value)
        assertEquals("commit/accessibility: only the isEditable counter may move", onlyAt(2), node.counts())
        assertEquals(
            "commit/accessibility: the call list must name the isEditable read",
            listOf(FakeFieldNode.READ_IS_EDITABLE),
            node.calls,
        )
    }

    @Test
    fun `reading isEnabled moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.isEnabled = false

        val value: Boolean = node.isEnabled

        assertFalse("commit/accessibility: the isEnabled read must return what was set", value)
        assertEquals("commit/accessibility: only the isEnabled counter may move", onlyAt(3), node.counts())
        assertEquals("commit/accessibility: the call list must name the isEnabled read", listOf(FakeFieldNode.READ_IS_ENABLED), node.calls)
    }

    @Test
    fun `reading isShowingHint moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.isShowingHint = true

        val value: Boolean = node.isShowingHint

        assertTrue("commit/accessibility: the isShowingHint read must return what was set", value)
        assertEquals("commit/accessibility: only the isShowingHint counter may move", onlyAt(4), node.counts())
        assertEquals(
            "commit/accessibility: the call list must name the isShowingHint read",
            listOf(FakeFieldNode.READ_IS_SHOWING_HINT),
            node.calls,
        )
    }

    @Test
    fun `reading maxTextLength moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.maxTextLength = 42

        val value: Int = node.maxTextLength

        assertEquals("commit/accessibility: the maxTextLength read must return what was set", 42, value)
        assertEquals("commit/accessibility: only the maxTextLength counter may move", onlyAt(5), node.counts())
        assertEquals(
            "commit/accessibility: the call list must name the maxTextLength read",
            listOf(FakeFieldNode.READ_MAX_TEXT_LENGTH),
            node.calls,
        )
    }

    @Test
    fun `reading the text moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.holdText("held")

        val value: String? = node.text

        assertEquals("commit/accessibility: the text read must return what the node holds", "held", value)
        assertEquals("commit/accessibility: only the text counter may move", onlyAt(6), node.counts())
        assertEquals("commit/accessibility: the call list must name the text read", listOf(FakeFieldNode.READ_TEXT), node.calls)
    }

    @Test
    fun `reading the selection start moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.selectionStart = 3

        val value: Int = node.selectionStart

        assertEquals("commit/accessibility: the selectionStart read must return what was set", 3, value)
        assertEquals("commit/accessibility: only the selectionStart counter may move", onlyAt(7), node.counts())
        assertEquals(
            "commit/accessibility: the call list must name the selectionStart read",
            listOf(FakeFieldNode.READ_SELECTION_START),
            node.calls,
        )
    }

    @Test
    fun `reading the selection end moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()
        node.selectionEnd = 5

        val value: Int = node.selectionEnd

        assertEquals("commit/accessibility: the selectionEnd read must return what was set", 5, value)
        assertEquals("commit/accessibility: only the selectionEnd counter may move", onlyAt(8), node.counts())
        assertEquals(
            "commit/accessibility: the call list must name the selectionEnd read",
            listOf(FakeFieldNode.READ_SELECTION_END),
            node.calls,
        )
    }

    @Test
    fun `refresh moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()

        val valid: Boolean = node.refresh()

        assertTrue("commit/accessibility: refresh must answer true by default", valid)
        assertEquals("commit/accessibility: only the refresh counter may move", onlyAt(9), node.counts())
        assertEquals("commit/accessibility: the call list must name the refresh call", listOf(FakeFieldNode.REFRESH), node.calls)
    }

    @Test
    fun `setText moves only its counter from 0 to 1 and records its argument`() {
        val node = FakeFieldNode()

        val took: Boolean = node.setText("new text")

        assertTrue("commit/accessibility: setText must answer true by default", took)
        assertEquals("commit/accessibility: only the setText counter may move", onlyAt(10), node.counts())
        assertEquals("commit/accessibility: the call list must name the setText call", listOf(FakeFieldNode.SET_TEXT), node.calls)
        assertEquals("commit/accessibility: the setText argument must be recorded", listOf("new text"), node.setTextArguments)
    }

    @Test
    fun `setSelection moves only its counter from 0 to 1 and records its arguments`() {
        val node = FakeFieldNode()

        val took: Boolean = node.setSelection(2, 4)

        assertTrue("commit/accessibility: setSelection must answer true by default", took)
        assertEquals("commit/accessibility: only the setSelection counter may move", onlyAt(11), node.counts())
        assertEquals("commit/accessibility: the call list must name the setSelection call", listOf(FakeFieldNode.SET_SELECTION), node.calls)
        assertEquals("commit/accessibility: the setSelection arguments must be recorded", listOf(Pair(2, 4)), node.setSelectionArguments)
    }

    @Test
    fun `release moves only its counter from 0 to 1`() {
        val node = FakeFieldNode()

        node.release()

        assertEquals("commit/accessibility: only the release counter may move", onlyAt(12), node.counts())
        assertEquals("commit/accessibility: the call list must name the release call", listOf(FakeFieldNode.RELEASE), node.calls)
    }

    @Test
    fun `a repeated call counts every time`() {
        val node = FakeFieldNode()

        val first: String? = node.text
        val second: String? = node.text
        node.release()
        node.release()

        assertEquals("commit/accessibility: both text reads must return the held text", first, second)
        assertEquals("commit/accessibility: two text reads must count two", 2, node.textReads)
        assertEquals("commit/accessibility: two releases must count two", 2, node.releaseCalls)
    }

    @Test
    fun `setting a property or the held text does not count as a call`() {
        val node = FakeFieldNode()

        node.packageName = "com.example.other"
        node.isPassword = true
        node.isEditable = false
        node.isEnabled = false
        node.isShowingHint = true
        node.maxTextLength = 9
        node.selectionStart = 1
        node.selectionEnd = 2
        node.holdText("held")

        assertEquals("commit/accessibility: setting state must not move any counter", List(13) { 0 }, node.counts())
        assertTrue("commit/accessibility: setting state must not add to the call list", node.calls.isEmpty())
    }

    @Test
    fun `the call list records every call in order`() {
        val node = FakeFieldNode()

        node.refresh()
        val packageName: String? = node.packageName
        val password: Boolean = node.isPassword
        val text: String? = node.text
        val start: Int = node.selectionStart
        val end: Int = node.selectionEnd
        node.setText("x")
        node.setSelection(1, 1)
        node.release()

        assertEquals(
            "commit/accessibility: the call list must hold every call in the order it was made",
            listOf(
                FakeFieldNode.REFRESH,
                FakeFieldNode.READ_PACKAGE_NAME,
                FakeFieldNode.READ_IS_PASSWORD,
                FakeFieldNode.READ_TEXT,
                FakeFieldNode.READ_SELECTION_START,
                FakeFieldNode.READ_SELECTION_END,
                FakeFieldNode.SET_TEXT,
                FakeFieldNode.SET_SELECTION,
                FakeFieldNode.RELEASE,
            ),
            node.calls,
        )
        assertTrue(
            "commit/accessibility: the reads must have returned the defaults",
            packageName != null && !password && text == "" && start == -1 && end == -1,
        )
    }

    @Test
    fun `two nodes do not share counters`() {
        val first = FakeFieldNode()
        val second = FakeFieldNode()

        first.release()

        assertEquals("commit/accessibility: the first node must count its release", 1, first.releaseCalls)
        assertEquals("commit/accessibility: the second node must not count the first node's release", 0, second.releaseCalls)
        assertTrue("commit/accessibility: the second node must have an empty call list", second.calls.isEmpty())
    }
}
