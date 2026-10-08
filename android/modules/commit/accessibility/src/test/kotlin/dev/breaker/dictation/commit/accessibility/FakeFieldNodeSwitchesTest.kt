package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure switches of the node double and its finder: a call that is told to answer false
 * or to throw does so, still counts, and leaves the held state alone; a node told to hold no
 * text returns null; the finder answers with its node or with null and counts its calls.
 */
internal class FakeFieldNodeSwitchesTest {

    /** The expected [FakeFieldNode.counts]: 1 at [index] and 0 everywhere else. */
    private fun onlyAt(index: Int): List<Int> = List(13) { position -> if (position == index) 1 else 0 }

    @Test
    fun `a refresh that is told to fail answers false`() {
        val node = FakeFieldNode()
        node.refreshResult = false

        val valid: Boolean = node.refresh()

        assertFalse("commit/accessibility: refresh must answer false when told to", valid)
        assertEquals("commit/accessibility: a refresh that answers false still counts", onlyAt(9), node.counts())
    }

    @Test
    fun `a refresh that is told to throw throws that exception and still counts`() {
        val node = FakeFieldNode()
        val failure = IllegalStateException("refresh failure")
        node.refreshFailure = failure

        val thrown: IllegalStateException = assertThrows(
            "commit/accessibility: refresh must throw when told to",
            IllegalStateException::class.java,
        ) { node.refresh() }

        assertSame("commit/accessibility: refresh must throw the exception it was given", failure, thrown)
        assertEquals("commit/accessibility: a refresh that throws still counts", onlyAt(9), node.counts())
        assertEquals("commit/accessibility: a refresh that throws is still in the call list", listOf(FakeFieldNode.REFRESH), node.calls)
    }

    @Test
    fun `a setText that is told to fail answers false and keeps the held text`() {
        val node = FakeFieldNode()
        node.holdText("before")
        node.setTextResult = false

        val took: Boolean = node.setText("after")

        assertFalse("commit/accessibility: setText must answer false when told to", took)
        assertEquals("commit/accessibility: a refused setText must leave the held text alone", "before", node.text)
        assertEquals("commit/accessibility: a refused setText still counts", 1, node.setTextCalls)
        assertEquals("commit/accessibility: a refused setText still records its argument", listOf("after"), node.setTextArguments)
    }

    @Test
    fun `a setText that is told to throw throws that exception and keeps the held text`() {
        val node = FakeFieldNode()
        node.holdText("before")
        val failure = IllegalStateException("setText failure")
        node.setTextFailure = failure

        val thrown: IllegalStateException = assertThrows(
            "commit/accessibility: setText must throw when told to",
            IllegalStateException::class.java,
        ) { node.setText("after") }

        assertSame("commit/accessibility: setText must throw the exception it was given", failure, thrown)
        assertEquals("commit/accessibility: a setText that throws still counts", 1, node.setTextCalls)
        assertEquals("commit/accessibility: a setText that throws must leave the held text alone", "before", node.text)
    }

    @Test
    fun `a setText that succeeds makes the node hold the new text`() {
        val node = FakeFieldNode()
        node.holdText("before")

        val took: Boolean = node.setText("after")

        assertTrue("commit/accessibility: setText must answer true by default", took)
        assertEquals("commit/accessibility: a successful setText must change the held text", "after", node.text)
    }

    @Test
    fun `a setSelection that is told to fail answers false and keeps the selection`() {
        val node = FakeFieldNode()
        node.selectionStart = 1
        node.selectionEnd = 1
        node.setSelectionResult = false

        val took: Boolean = node.setSelection(4, 5)

        assertFalse("commit/accessibility: setSelection must answer false when told to", took)
        assertEquals("commit/accessibility: a refused setSelection must leave the start alone", 1, node.selectionStart)
        assertEquals("commit/accessibility: a refused setSelection must leave the end alone", 1, node.selectionEnd)
        assertEquals("commit/accessibility: a refused setSelection still counts", 1, node.setSelectionCalls)
    }

    @Test
    fun `a setSelection that is told to throw throws that exception and keeps the selection`() {
        val node = FakeFieldNode()
        node.selectionStart = 1
        node.selectionEnd = 1
        val failure = IllegalStateException("setSelection failure")
        node.setSelectionFailure = failure

        val thrown: IllegalStateException = assertThrows(
            "commit/accessibility: setSelection must throw when told to",
            IllegalStateException::class.java,
        ) { node.setSelection(4, 5) }

        assertSame("commit/accessibility: setSelection must throw the exception it was given", failure, thrown)
        assertEquals("commit/accessibility: a setSelection that throws still counts", 1, node.setSelectionCalls)
        assertEquals("commit/accessibility: a setSelection that throws must leave the start alone", 1, node.selectionStart)
    }

    @Test
    fun `a setSelection that succeeds makes the node hold the new selection`() {
        val node = FakeFieldNode()

        node.setSelection(4, 5)

        assertEquals("commit/accessibility: a successful setSelection must change the start", 4, node.selectionStart)
        assertEquals("commit/accessibility: a successful setSelection must change the end", 5, node.selectionEnd)
    }

    @Test
    fun `a node told to hold no text returns null from the text read`() {
        val node = FakeFieldNode()
        node.holdText(null)

        val value: String? = node.text

        assertNull("commit/accessibility: the text read must return null when the node holds none", value)
        assertEquals("commit/accessibility: a null text read still counts", 1, node.textReads)
    }

    @Test
    fun `the finder answers with its node and counts each call`() {
        val node = FakeFieldNode()
        val finder = FakeFocusedNodeFinder(node)

        val found: FieldNode? = finder.findInputFocus()

        assertSame("commit/accessibility: the finder must answer with its node", node, found)
        assertEquals("commit/accessibility: one find must count one", 1, finder.findCalls)
        assertEquals("commit/accessibility: asking the finder must not touch the node", List(13) { 0 }, node.counts())
    }

    @Test
    fun `the finder answers null when it has no node and still counts`() {
        val finder = FakeFocusedNodeFinder(null)

        val found: FieldNode? = finder.findInputFocus()

        assertNull("commit/accessibility: a finder with no node must answer null", found)
        assertEquals("commit/accessibility: a find that answers null still counts", 1, finder.findCalls)
    }

    @Test
    fun `a finder and a node sharing a list record their calls in one order`() {
        val calls: MutableList<String> = ArrayList()
        val node = FakeFieldNode(calls)
        val finder = FakeFocusedNodeFinder(node, calls)

        val found: FieldNode? = finder.findInputFocus()
        found?.refresh()
        found?.release()

        assertEquals(
            "commit/accessibility: the shared list must hold the find, then the node calls",
            listOf(FakeFocusedNodeFinder.FIND_INPUT_FOCUS, FakeFieldNode.REFRESH, FakeFieldNode.RELEASE),
            calls,
        )
    }
}
