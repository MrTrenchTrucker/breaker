package dev.breaker.dictation.commit.accessibility

import dev.breaker.dictation.commit.FieldCommit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every way [NodeFocusedField] turns a text away before the insert rules run (no text, no
 * focused field, a node that is no longer valid, this app's own field, a password field, a
 * field that is not editable or not enabled), with the counters of the node double: the
 * check that refuses must be the last member touched, and nothing after it may be read or
 * written. In particular the field's text is never read for a field that is turned away.
 * The refusals by the insert rules and by the setText and setSelection calls are in
 * `NodeFocusedFieldSetTextRefusalTest`. The expected counts and call lists are worked out
 * by hand from the order of the checks.
 */
internal class NodeFocusedFieldRefusalTest {

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

    /** Commit "X" and assert it is refused, with exactly these node [expectedCounts] and exactly this [expectedCalls] list. */
    private fun expectTurnedAway(label: String, rig: Rig, expectedCounts: List<Int>, expectedCalls: List<String>) {
        val result: FieldCommit = rig.field.commitText("X")
        assertEquals("commit/accessibility: " + label + ": the commit must be refused", FieldCommit.REFUSED, result)
        assertEquals(
            "commit/accessibility: " + label + ": the node must be touched exactly as far as the refusing check",
            expectedCounts,
            rig.node.counts(),
        )
        assertEquals(
            "commit/accessibility: " + label + ": the calls must stop at the refusing check, then release",
            expectedCalls,
            rig.calls,
        )
    }

    @Test
    fun `an empty text is refused without looking at the screen`() {
        val rig = Rig()

        val result: FieldCommit = rig.field.commitText("")

        assertEquals("commit/accessibility: an empty text must be refused", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: an empty text must not ask the finder at all", 0, rig.finder.findCalls)
        assertEquals("commit/accessibility: an empty text must not touch the node", counts(), rig.node.counts())
        assertEquals("commit/accessibility: an empty text must make no call at all", emptyList<String>(), rig.calls)
    }

    @Test
    fun `an empty text is refused before a password field is even looked up`() {
        val rig = Rig()
        rig.node.isPassword = true

        val result: FieldCommit = rig.field.commitText("")

        assertEquals("commit/accessibility: an empty text must be refused", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: an empty text must not ask the finder, whatever the field is", 0, rig.finder.findCalls)
        assertEquals("commit/accessibility: an empty text must not read the password flag", counts(), rig.node.counts())
    }

    @Test
    fun `no focused field is a refusal and there is nothing to release`() {
        val rig = Rig()
        rig.finder.node = null

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: no focused field must be refused", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: the finder must be asked once", 1, rig.finder.findCalls)
        assertEquals("commit/accessibility: no node came back, so none may be touched", counts(), rig.node.counts())
        assertEquals(
            "commit/accessibility: the only call must be the lookup",
            listOf(FakeFocusedNodeFinder.FIND_INPUT_FOCUS),
            rig.calls,
        )
    }

    @Test
    fun `a node that is no longer valid is refused after the refresh and nothing else is read`() {
        val rig = Rig()
        rig.node.refreshResult = false

        expectTurnedAway(
            "refresh answers false",
            rig,
            counts(refresh = 1, release = 1),
            listOf(FakeFocusedNodeFinder.FIND_INPUT_FOCUS, FakeFieldNode.REFRESH, FakeFieldNode.RELEASE),
        )
    }

    @Test
    fun `a refresh that throws is a refusal and the node is still released`() {
        val rig = Rig()
        rig.node.refreshFailure = IllegalStateException("refresh failed")

        expectTurnedAway(
            "refresh throws",
            rig,
            counts(refresh = 1, release = 1),
            listOf(FakeFocusedNodeFinder.FIND_INPUT_FOCUS, FakeFieldNode.REFRESH, FakeFieldNode.RELEASE),
        )
    }

    @Test
    fun `a field of this app is refused after the package read and no flag is read`() {
        val rig = Rig()
        rig.node.packageName = rig.ownPackage

        expectTurnedAway(
            "own package",
            rig,
            counts(packageName = 1, refresh = 1, release = 1),
            listOf(
                FakeFocusedNodeFinder.FIND_INPUT_FOCUS,
                FakeFieldNode.REFRESH,
                FakeFieldNode.READ_PACKAGE_NAME,
                FakeFieldNode.RELEASE,
            ),
        )
    }

    @Test
    fun `a field whose package is not known goes on`() {
        // A null package is not this app's package, so the insert continues and reads every member once.
        val rig = Rig()
        rig.node.packageName = null
        rig.node.holdText("abc")
        rig.node.selectionStart = 3
        rig.node.selectionEnd = 3

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: a field with no known package must still take the text", FieldCommit.ACCEPTED, result)
        assertEquals("commit/accessibility: the package must be read once", 1, rig.node.packageNameReads)
        assertEquals("commit/accessibility: setText must be called once", listOf("abcX"), rig.node.setTextArguments)
    }

    @Test
    fun `a package that only looks like the own package goes on`() {
        // The check is equality: a longer name that starts with ours, and a shorter name that ours starts with, are other apps.
        val longer = Rig()
        longer.node.packageName = longer.ownPackage + ".other"
        val shorter = Rig()
        shorter.node.packageName = "com.breaker"

        assertEquals(
            "commit/accessibility: a package that starts with this app's package is another app",
            FieldCommit.ACCEPTED,
            longer.field.commitText("X"),
        )
        assertEquals(
            "commit/accessibility: a package that is the start of this app's package is another app",
            FieldCommit.ACCEPTED,
            shorter.field.commitText("X"),
        )
        assertEquals("commit/accessibility: the longer package must reach setText", 1, longer.node.setTextCalls)
        assertEquals("commit/accessibility: the shorter package must reach setText", 1, shorter.node.setTextCalls)
    }

    @Test
    fun `a package that differs from the own package only in letter case is another app`() {
        // Android package names are case-sensitive, so the same letters in another case name another app.
        val rig = Rig()
        rig.node.packageName = "Com.Breaker.Own"
        rig.node.holdText("abc")
        rig.node.selectionStart = 3
        rig.node.selectionEnd = 3

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: a package that differs only in letter case is another app", FieldCommit.ACCEPTED, result)
        assertEquals("commit/accessibility: the merged text must reach setText", listOf("abcX"), rig.node.setTextArguments)
        assertEquals("commit/accessibility: the finder must be asked once", 1, rig.finder.findCalls)
        assertEquals("commit/accessibility: the node must be released once", 1, rig.node.releaseCalls)
    }

    @Test
    fun `a password field is refused after the password flag and its text is never read`() {
        val rig = Rig()
        rig.node.isPassword = true
        rig.node.holdText("SECRET-DICTATION-7f3a")

        expectTurnedAway(
            "password",
            rig,
            counts(packageName = 1, password = 1, refresh = 1, release = 1),
            listOf(
                FakeFocusedNodeFinder.FIND_INPUT_FOCUS,
                FakeFieldNode.REFRESH,
                FakeFieldNode.READ_PACKAGE_NAME,
                FakeFieldNode.READ_IS_PASSWORD,
                FakeFieldNode.RELEASE,
            ),
        )
    }

    @Test
    fun `a password field that is also not editable and not enabled stops at the password flag`() {
        val rig = Rig()
        rig.node.isPassword = true
        rig.node.isEditable = false
        rig.node.isEnabled = false

        expectTurnedAway(
            "password, not editable, not enabled",
            rig,
            counts(packageName = 1, password = 1, refresh = 1, release = 1),
            listOf(
                FakeFocusedNodeFinder.FIND_INPUT_FOCUS,
                FakeFieldNode.REFRESH,
                FakeFieldNode.READ_PACKAGE_NAME,
                FakeFieldNode.READ_IS_PASSWORD,
                FakeFieldNode.RELEASE,
            ),
        )
        assertEquals("commit/accessibility: a password field must never get a text read", 0, rig.node.textReads)
        assertEquals("commit/accessibility: a password field must never get a setText", 0, rig.node.setTextCalls)
        assertEquals("commit/accessibility: a password field must never get a setSelection", 0, rig.node.setSelectionCalls)
    }

    @Test
    fun `a field that is not editable is refused after the editable flag and its text is never read`() {
        val rig = Rig()
        rig.node.isEditable = false
        rig.node.isEnabled = false

        expectTurnedAway(
            "not editable",
            rig,
            counts(packageName = 1, password = 1, editable = 1, refresh = 1, release = 1),
            listOf(
                FakeFocusedNodeFinder.FIND_INPUT_FOCUS,
                FakeFieldNode.REFRESH,
                FakeFieldNode.READ_PACKAGE_NAME,
                FakeFieldNode.READ_IS_PASSWORD,
                FakeFieldNode.READ_IS_EDITABLE,
                FakeFieldNode.RELEASE,
            ),
        )
    }

    @Test
    fun `a field that is not enabled is refused after the enabled flag and its text is never read`() {
        val rig = Rig()
        rig.node.isEnabled = false

        expectTurnedAway(
            "not enabled",
            rig,
            counts(packageName = 1, password = 1, editable = 1, enabled = 1, refresh = 1, release = 1),
            listOf(
                FakeFocusedNodeFinder.FIND_INPUT_FOCUS,
                FakeFieldNode.REFRESH,
                FakeFieldNode.READ_PACKAGE_NAME,
                FakeFieldNode.READ_IS_PASSWORD,
                FakeFieldNode.READ_IS_EDITABLE,
                FakeFieldNode.READ_IS_ENABLED,
                FakeFieldNode.RELEASE,
            ),
        )
    }
}
