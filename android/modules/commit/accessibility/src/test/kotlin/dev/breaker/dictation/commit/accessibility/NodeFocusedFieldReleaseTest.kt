package dev.breaker.dictation.commit.accessibility

import dev.breaker.dictation.commit.FieldCommit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A [FieldNode] that forwards every member to a [FakeFieldNode], so the double still counts
 * and records each call, and then throws [failure] from each member named in [failing].
 *
 * The member names are the constants of [FakeFieldNode]. The call is counted before it
 * throws, so a test sees both the call and the exception. Each test builds its own.
 */
internal class FailingMemberNode(
    val inner: FakeFieldNode,
    private val failing: Set<String>,
    private val failure: Exception = IllegalStateException("member failed"),
) : FieldNode {

    private fun <T> afterCall(member: String, result: T): T {
        if (member in failing) throw failure
        return result
    }

    override val packageName: String?
        get() = afterCall(FakeFieldNode.READ_PACKAGE_NAME, inner.packageName)

    override val isPassword: Boolean
        get() = afterCall(FakeFieldNode.READ_IS_PASSWORD, inner.isPassword)

    override val isEditable: Boolean
        get() = afterCall(FakeFieldNode.READ_IS_EDITABLE, inner.isEditable)

    override val isEnabled: Boolean
        get() = afterCall(FakeFieldNode.READ_IS_ENABLED, inner.isEnabled)

    override val isShowingHint: Boolean
        get() = afterCall(FakeFieldNode.READ_IS_SHOWING_HINT, inner.isShowingHint)

    override val maxTextLength: Int
        get() = afterCall(FakeFieldNode.READ_MAX_TEXT_LENGTH, inner.maxTextLength)

    override val text: String?
        get() = afterCall(FakeFieldNode.READ_TEXT, inner.text)

    override val selectionStart: Int
        get() = afterCall(FakeFieldNode.READ_SELECTION_START, inner.selectionStart)

    override val selectionEnd: Int
        get() = afterCall(FakeFieldNode.READ_SELECTION_END, inner.selectionEnd)

    override fun refresh(): Boolean = afterCall(FakeFieldNode.REFRESH, inner.refresh())

    override fun setText(text: String): Boolean = afterCall(FakeFieldNode.SET_TEXT, inner.setText(text))

    override fun setSelection(start: Int, end: Int): Boolean =
        afterCall(FakeFieldNode.SET_SELECTION, inner.setSelection(start, end))

    override fun release() {
        inner.release()
        afterCall(FakeFieldNode.RELEASE, Unit)
    }

    /** Every member name, in the order of [FakeFieldNode.counts]. */
    companion object {
        val MEMBERS: List<String> = listOf(
            FakeFieldNode.READ_PACKAGE_NAME,
            FakeFieldNode.READ_IS_PASSWORD,
            FakeFieldNode.READ_IS_EDITABLE,
            FakeFieldNode.READ_IS_ENABLED,
            FakeFieldNode.READ_IS_SHOWING_HINT,
            FakeFieldNode.READ_MAX_TEXT_LENGTH,
            FakeFieldNode.READ_TEXT,
            FakeFieldNode.READ_SELECTION_START,
            FakeFieldNode.READ_SELECTION_END,
            FakeFieldNode.REFRESH,
            FakeFieldNode.SET_TEXT,
            FakeFieldNode.SET_SELECTION,
            FakeFieldNode.RELEASE,
        )
    }
}

/**
 * The node that [NodeFocusedField] finds is given back exactly once on every way out: an
 * accepted insert, every refusal, a node whose members throw, and a release that throws.
 * The release counter and the call list come from the node double, not from the code under test.
 */
internal class NodeFocusedFieldReleaseTest {

    /** A field over a fresh double behind a [FailingMemberNode] that throws from the members in [failing]. */
    private class Rig(failing: Set<String> = emptySet()) {
        val ownPackage: String = "com.breaker.own"
        val calls: MutableList<String> = ArrayList()
        val inner: FakeFieldNode = FakeFieldNode(calls)
        val finder: FakeFocusedNodeFinder = FakeFocusedNodeFinder(FailingMemberNode(inner, failing), calls)
        val field: NodeFocusedField = NodeFocusedField(finder, ownPackage)
    }

    /** One way a commit can go: how to set the node up, and what the commit must answer. */
    private class Path(val label: String, val expected: FieldCommit, val configure: (Rig) -> Unit)

    private fun paths(): List<Path> = listOf(
        Path("accepted", FieldCommit.ACCEPTED) { },
        Path("refresh answers false", FieldCommit.REFUSED) { it.inner.refreshResult = false },
        Path("refresh throws", FieldCommit.REFUSED) { it.inner.refreshFailure = IllegalStateException("refresh failed") },
        Path("own package", FieldCommit.REFUSED) { it.inner.packageName = it.ownPackage },
        Path("password", FieldCommit.REFUSED) { it.inner.isPassword = true },
        Path("not editable", FieldCommit.REFUSED) { it.inner.isEditable = false },
        Path("not enabled", FieldCommit.REFUSED) { it.inner.isEnabled = false },
        Path("text too long for the limit", FieldCommit.REFUSED) { it.inner.maxTextLength = 0 },
        Path("setText answers false", FieldCommit.REFUSED) { it.inner.setTextResult = false },
        Path("setText throws", FieldCommit.REFUSED) { it.inner.setTextFailure = IllegalStateException("setText failed") },
        Path("setSelection answers false", FieldCommit.ACCEPTED) { it.inner.setSelectionResult = false },
        Path("setSelection throws", FieldCommit.ACCEPTED) {
            it.inner.setSelectionFailure = IllegalStateException("setSelection failed")
        },
    )

    /** Assert the node was released exactly once and that the release was the last call made on it. */
    private fun expectReleasedOnce(label: String, rig: Rig) {
        assertEquals("commit/accessibility: " + label + ": release must be called exactly once", 1, rig.inner.releaseCalls)
        assertEquals(
            "commit/accessibility: " + label + ": release must appear exactly once in the call list",
            1,
            rig.calls.count { it == FakeFieldNode.RELEASE },
        )
        assertEquals(
            "commit/accessibility: " + label + ": nothing may touch the node after it was released",
            FakeFieldNode.RELEASE,
            rig.calls.last(),
        )
    }

    @Test
    fun `the node is released exactly once on an accepted insert and on every refusal`() {
        // Each path ends in the answer the order of the checks gives: refused before the write,
        // or refused by a write that failed, or accepted (a cursor that fails to move is still accepted).
        for (path in paths()) {
            val rig = Rig()
            path.configure(rig)

            val result: FieldCommit = rig.field.commitText("X")

            assertEquals("commit/accessibility: " + path.label + ": wrong answer", path.expected, result)
            assertEquals("commit/accessibility: " + path.label + ": the finder must be asked once", 1, rig.finder.findCalls)
            expectReleasedOnce(path.label, rig)
        }
    }

    @Test
    fun `no node found means nothing to release`() {
        val rig = Rig()
        rig.finder.node = null

        rig.field.commitText("X")

        assertEquals("commit/accessibility: a node that was never found must not be released", 0, rig.inner.releaseCalls)
    }

    @Test
    fun `a node with one throwing member is still released once`() {
        // Every member except release itself throws, one at a time, and the node goes back each time.
        for (member in FailingMemberNode.MEMBERS) {
            if (member == FakeFieldNode.RELEASE) continue
            val rig = Rig(setOf(member))

            rig.field.commitText("X")

            expectReleasedOnce("throwing " + member, rig)
        }
    }

    @Test
    fun `a release that throws does not change an accepted insert`() {
        val rig = Rig(setOf(FakeFieldNode.RELEASE))

        val result: FieldCommit = rig.field.commitText("X")

        assertEquals("commit/accessibility: a throwing release must not turn an accepted insert into a refusal", FieldCommit.ACCEPTED, result)
        assertEquals("commit/accessibility: the text must have been set before the release", 1, rig.inner.setTextCalls)
        expectReleasedOnce("accepted insert, release throws", rig)
    }

    @Test
    fun `a release that throws leaves a refusal a refusal and is not retried`() {
        val password = Rig(setOf(FakeFieldNode.RELEASE))
        password.inner.isPassword = true
        val stale = Rig(setOf(FakeFieldNode.RELEASE))
        stale.inner.refreshResult = false
        val failedWrite = Rig(setOf(FakeFieldNode.RELEASE, FakeFieldNode.SET_TEXT))

        assertEquals(
            "commit/accessibility: a password refusal must stay a refusal when release throws",
            FieldCommit.REFUSED,
            password.field.commitText("X"),
        )
        assertEquals(
            "commit/accessibility: a stale node refusal must stay a refusal when release throws",
            FieldCommit.REFUSED,
            stale.field.commitText("X"),
        )
        assertEquals(
            "commit/accessibility: a failed write must stay a refusal when release throws",
            FieldCommit.REFUSED,
            failedWrite.field.commitText("X"),
        )
        expectReleasedOnce("password, release throws", password)
        expectReleasedOnce("stale node, release throws", stale)
        expectReleasedOnce("failed write, release throws", failedWrite)
        assertEquals("commit/accessibility: no cursor after a failed write", 0, failedWrite.inner.setSelectionCalls)
    }

    @Test
    fun `a node that was handed back is never used again by a later call`() {
        // The first node is used and released by the first call; the finder then answers with a
        // second node, and the second call must touch only that one.
        val calls: MutableList<String> = ArrayList()
        val first = FakeFieldNode(calls)
        val second = FakeFieldNode(calls)
        val finder = FakeFocusedNodeFinder(first, calls)
        val field = NodeFocusedField(finder, "com.breaker.own")

        field.commitText("X")
        val firstAfterOwnCall: List<Int> = first.counts()
        finder.node = second
        field.commitText("Y")

        assertEquals(
            "commit/accessibility: the first call must use and release its node once, each member once",
            List(13) { 1 },
            firstAfterOwnCall,
        )
        assertEquals("commit/accessibility: the second call must not touch the node of the first", firstAfterOwnCall, first.counts())
        assertEquals("commit/accessibility: the second call must use and release its own node", List(13) { 1 }, second.counts())
        assertEquals("commit/accessibility: every call must ask the finder again", 2, finder.findCalls)
    }
}
