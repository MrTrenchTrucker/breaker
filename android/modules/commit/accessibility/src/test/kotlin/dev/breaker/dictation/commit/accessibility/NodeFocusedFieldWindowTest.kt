package dev.breaker.dictation.commit.accessibility

import dev.breaker.dictation.commit.FieldCommit
import dev.breaker.dictation.commit.TakeStartAnchor
import dev.breaker.dictation.commit.TakeStartWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The take-start window ownership check in [NodeFocusedField].
 *
 * A take records the window it started in. A commit compares the window of the live node it is
 * about to insert into with that record. No record means the check is inactive and an insert goes
 * ahead exactly as before. A record that names no window, or names another window or another
 * package, answers NO_FIELD, so the text goes to the clipboard through the existing no-field path
 * and no refusal sentence is shown. The node double counts every read, so each test also shows
 * that a refused window is never read for its text.
 */
internal class NodeFocusedFieldWindowTest {

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

    @Before
    fun setUp() {
        TakeStartAnchor.clear()
    }

    @After
    fun tearDown() {
        TakeStartAnchor.clear()
    }

    @Test
    fun `with no anchor captured a normal insert still happens`() {
        // The check is inactive: no take was captured, so the window is not even read.
        val rig = Rig()
        rig.node.windowId = 7
        rig.holds("abc", 1, 1)

        assertEquals("commit/accessibility: an inactive check must not refuse the insert", FieldCommit.ACCEPTED, rig.field.commitText("X"))
        assertEquals("commit/accessibility: the text must go in as usual", listOf("aXbc"), rig.node.setTextArguments)
        assertEquals("commit/accessibility: an inactive check must not read the window", 0, rig.node.windowIdReads)
    }

    @Test
    fun `an anchor that matches the window and the package lets the insert happen`() {
        val rig = Rig()
        rig.node.windowId = 7
        rig.holds("abc", 1, 1)
        TakeStartAnchor.capture(TakeStartWindow(7, "com.example.target"))

        assertEquals("commit/accessibility: a matching window must not refuse the insert", FieldCommit.ACCEPTED, rig.field.commitText("X"))
        assertEquals("commit/accessibility: the text must go in as usual", listOf("aXbc"), rig.node.setTextArguments)
        assertEquals("commit/accessibility: the window must be read once", 1, rig.node.windowIdReads)
    }

    @Test
    fun `an anchor from another window answers NO_FIELD and reads no text`() {
        val rig = Rig()
        rig.node.windowId = 7
        rig.holds("abc", 1, 1)
        TakeStartAnchor.capture(TakeStartWindow(8, "com.example.target"))

        assertEquals("commit/accessibility: another window must answer NO_FIELD", FieldCommit.NO_FIELD, rig.field.commitText("X"))
        assertEquals("commit/accessibility: a refused window must not be written to", 0, rig.node.setTextCalls)
        assertEquals("commit/accessibility: a refused window must not have its text read", 0, rig.node.textReads)
        assertTrue(
            "commit/accessibility: the text read must not appear in the call list",
            !rig.calls.contains(FakeFieldNode.READ_TEXT),
        )
    }

    @Test
    fun `an anchor from another package with the same window answers NO_FIELD`() {
        val rig = Rig()
        rig.node.windowId = 7
        rig.holds("abc", 1, 1)
        TakeStartAnchor.capture(TakeStartWindow(7, "com.example.other"))

        assertEquals("commit/accessibility: another package must answer NO_FIELD", FieldCommit.NO_FIELD, rig.field.commitText("X"))
        assertEquals("commit/accessibility: a refused package must not be written to", 0, rig.node.setTextCalls)
        assertEquals("commit/accessibility: a refused package must not have its text read", 0, rig.node.textReads)
    }

    @Test
    fun `an anchor of no window and no package answers NO_FIELD even with a real window`() {
        // A take that started with no focused field recorded (null, null): fail closed.
        val rig = Rig()
        rig.node.windowId = 7
        rig.holds("abc", 1, 1)
        TakeStartAnchor.capture(TakeStartWindow(null, null))

        assertEquals("commit/accessibility: an anchor with no window must answer NO_FIELD", FieldCommit.NO_FIELD, rig.field.commitText("X"))
        assertEquals("commit/accessibility: the node has a real window, so nothing may be written", 0, rig.node.setTextCalls)
        assertEquals("commit/accessibility: the node text must not be read", 0, rig.node.textReads)
    }

    @Test
    fun `a node with no window id answers NO_FIELD against a real anchor`() {
        val rig = Rig()
        rig.node.windowId = null
        rig.holds("abc", 1, 1)
        TakeStartAnchor.capture(TakeStartWindow(7, "com.example.target"))

        assertEquals("commit/accessibility: a node with no window must answer NO_FIELD", FieldCommit.NO_FIELD, rig.field.commitText("X"))
        assertEquals("commit/accessibility: nothing may be written", 0, rig.node.setTextCalls)
        assertEquals("commit/accessibility: the text must not be read", 0, rig.node.textReads)
    }

    @Test
    fun `the earlier refusals still win over a window mismatch`() {
        // A password field in the wrong window is refused for being a password, and its window
        // is never reached; a not-editable and a disabled field likewise.
        val password = Rig()
        password.node.windowId = 7
        password.node.isPassword = true
        TakeStartAnchor.capture(TakeStartWindow(8, "com.example.target"))

        assertEquals(
            "commit/accessibility: the password refusal must run before the window check",
            FieldCommit.REFUSED,
            password.field.commitText("X"),
        )
        assertEquals("commit/accessibility: the password path must not read the window", 0, password.node.windowIdReads)
        assertEquals("commit/accessibility: a password field must not have its text read", 0, password.node.textReads)

        val notEditable = Rig()
        notEditable.node.windowId = 7
        notEditable.node.isEditable = false
        TakeStartAnchor.capture(TakeStartWindow(8, "com.example.target"))

        assertEquals(
            "commit/accessibility: the editable refusal must run before the window check",
            FieldCommit.REFUSED,
            notEditable.field.commitText("X"),
        )
        assertEquals("commit/accessibility: the editable path must not read the window", 0, notEditable.node.windowIdReads)

        val disabled = Rig()
        disabled.node.windowId = 7
        disabled.node.isEnabled = false
        TakeStartAnchor.capture(TakeStartWindow(8, "com.example.target"))

        assertEquals(
            "commit/accessibility: the enabled refusal must run before the window check",
            FieldCommit.REFUSED,
            disabled.field.commitText("X"),
        )
        assertEquals("commit/accessibility: the enabled path must not read the window", 0, disabled.node.windowIdReads)
    }

    @Test
    fun `focusedWindow reads the live window and gives the node back once`() {
        val rig = Rig()
        rig.node.windowId = 7
        rig.node.packageName = "com.example.target"

        val window: TakeStartWindow? = rig.field.focusedWindow()

        assertEquals(
            "commit/accessibility: the live window id and package must be returned",
            TakeStartWindow(7, "com.example.target"),
            window,
        )
        assertEquals("commit/accessibility: the node must be given back exactly once", 1, rig.node.releaseCalls)
        assertEquals("commit/accessibility: no text property may be read", 0, rig.node.textReads)
        assertTrue(
            "commit/accessibility: the text read must not appear in the call list",
            !rig.calls.contains(FakeFieldNode.READ_TEXT),
        )
    }

    @Test
    fun `focusedWindow with no focused node returns null and releases nothing`() {
        val rig = Rig()
        rig.finder.node = null

        assertNull("commit/accessibility: no focused node must give a null window", rig.field.focusedWindow())
        assertEquals("commit/accessibility: nothing to release", 0, rig.node.releaseCalls)
        assertEquals("commit/accessibility: the window must not be read", 0, rig.node.windowIdReads)
    }

    @Test
    fun `a window mismatch is NO_FIELD and not REFUSED so no refusal sentence is shown`() {
        val rig = Rig()
        rig.node.windowId = 7
        rig.holds("abc", 1, 1)
        TakeStartAnchor.capture(TakeStartWindow(8, "com.example.target"))

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: the mismatch must be the no-field outcome", FieldCommit.NO_FIELD, result)
        assertTrue("commit/accessibility: the mismatch must not be a refusal", result != FieldCommit.REFUSED)
    }

    @Test
    fun `a spoof window in another package is refused`() {
        // Take start: window 1 in com.real.notes. Commit: window 2 in com.spoof.notes, a field
        // body that is editable and not a password, showing the same hint text as the real field.
        val rig = Rig()
        rig.node.windowId = 2
        rig.node.packageName = "com.spoof.notes"
        rig.node.isShowingHint = true
        rig.holds("New note", 0, 0)
        TakeStartAnchor.capture(TakeStartWindow(1, "com.real.notes"))

        assertEquals("commit/accessibility: a spoof window in another package must answer NO_FIELD", FieldCommit.NO_FIELD, rig.field.commitText("X"))
        assertEquals("commit/accessibility: a refused spoof window must not be written to", 0, rig.node.setTextCalls)
        assertEquals("commit/accessibility: a refused spoof window must not have its text read", 0, rig.node.textReads)
        assertTrue(
            "commit/accessibility: the text read must not appear in the call list",
            !rig.calls.contains(FakeFieldNode.READ_TEXT),
        )
    }

    @Test
    fun `a spoof window that copies our package is refused`() {
        // The spoof copies the real package but sits in another window: ONLY the window id differs,
        // so a package-only check would let it in and a real window check must refuse it.
        val rig = Rig()
        rig.node.windowId = 2
        rig.node.packageName = "com.real.notes"
        rig.node.isShowingHint = true
        rig.holds("New note", 0, 0)
        TakeStartAnchor.capture(TakeStartWindow(1, "com.real.notes"))

        assertEquals("commit/accessibility: a spoof window copying our package must answer NO_FIELD", FieldCommit.NO_FIELD, rig.field.commitText("X"))
        assertEquals("commit/accessibility: a refused spoof window must not be written to", 0, rig.node.setTextCalls)
        assertEquals("commit/accessibility: a refused spoof window must not have its text read", 0, rig.node.textReads)
        assertTrue(
            "commit/accessibility: the text read must not appear in the call list",
            !rig.calls.contains(FakeFieldNode.READ_TEXT),
        )
    }
}
