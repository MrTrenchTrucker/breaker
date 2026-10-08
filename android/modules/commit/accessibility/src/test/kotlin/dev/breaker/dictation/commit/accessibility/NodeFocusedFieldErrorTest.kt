package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * What [NodeFocusedField] does with an [Error], which is not an [Exception].
 *
 * Only an Exception is turned into a refusal. An Error (an out-of-memory condition, for one)
 * must leave `commitText` untouched, and a node that was found is still given back exactly once
 * on the way out. Each catch in the field has its own case: the outer catch is reached by an
 * Error from the finder or from any node member before the cursor call; the guard on the
 * cursor call only by an Error from `setSelection`; the guard on the release only by an
 * Error from `release`. Widening any one of the three to catch every Throwable lets an Error
 * through as an answer, and the matching test below then fails.
 */
internal class NodeFocusedFieldErrorTest {

    private companion object {
        const val OWN_PACKAGE: String = "com.breaker.own"

        /** The members used before and including the text write, in the order the field uses them. */
        val MEMBERS_UP_TO_THE_WRITE: List<String> = listOf(
            FakeFieldNode.REFRESH,
            FakeFieldNode.READ_PACKAGE_NAME,
            FakeFieldNode.READ_IS_PASSWORD,
            FakeFieldNode.READ_IS_EDITABLE,
            FakeFieldNode.READ_IS_ENABLED,
            FakeFieldNode.READ_IS_SHOWING_HINT,
            FakeFieldNode.READ_MAX_TEXT_LENGTH,
            FakeFieldNode.READ_TEXT,
            FakeFieldNode.READ_SELECTION_START,
            FakeFieldNode.READ_SELECTION_END,
            FakeFieldNode.SET_TEXT,
        )
    }

    /** A failure that is an Error and not an Exception; it carries no message and no text. */
    internal class StepError : Error()

    /**
     * A [FieldNode] that forwards every member to a [FakeFieldNode], so the double still counts
     * and records each call, and then throws [failure] from the one member named [failing].
     * The call is counted before it throws.
     */
    internal class ErrorNode(
        private val inner: FakeFieldNode,
        private val failing: String,
        private val failure: StepError,
    ) : FieldNode {

        private fun <T> afterCall(member: String, result: T): T {
            if (member == failing) throw failure
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
    }

    /**
     * A field over a fresh double holding "abc" with the cursor at 1, behind an [ErrorNode] that
     * throws [failure] from the member [failing]. Everything else about the node is clean, so an
     * accepted insert reaches every member.
     */
    private class Rig(failing: String) {
        val calls: MutableList<String> = ArrayList()
        val inner: FakeFieldNode = FakeFieldNode(calls)
        val failure: StepError = StepError()
        val finder: FakeFocusedNodeFinder = FakeFocusedNodeFinder(ErrorNode(inner, failing, failure), calls)
        val field: NodeFocusedField = NodeFocusedField(finder, OWN_PACKAGE)

        init {
            inner.holdText("abc")
            inner.selectionStart = 1
            inner.selectionEnd = 1
        }
    }

    /** Commit text on [rig] and assert the very Error it was set up to throw comes out of `commitText`. */
    private fun expectErrorLeaves(label: String, rig: Rig) {
        val thrown: StepError = assertThrows(
            "commit/accessibility: " + label + ": an Error must leave commitText and not become an answer",
            StepError::class.java,
        ) { rig.field.commitText("X") }
        assertSame("commit/accessibility: " + label + ": the Error must come out as it went in", rig.failure, thrown)
    }

    /** Assert the node was given back exactly once and nothing was called on it after that. */
    private fun expectGivenBackOnceAndLast(label: String, rig: Rig) {
        assertEquals("commit/accessibility: " + label + ": the node must be given back exactly once", 1, rig.inner.releaseCalls)
        assertEquals(
            "commit/accessibility: " + label + ": the release must be the last call on the node",
            FakeFieldNode.RELEASE,
            rig.calls.last(),
        )
    }

    @Test
    fun `an Error from the finder leaves commitText`() {
        // Reached only through the outer catch: the finder runs before any node exists.
        val failure = StepError()
        var asks = 0
        val field = NodeFocusedField(
            FocusedNodeFinder {
                asks++
                throw failure
            },
            OWN_PACKAGE,
        )

        val thrown: StepError = assertThrows(
            "commit/accessibility: an Error from the finder must leave commitText and not become a refusal",
            StepError::class.java,
        ) { field.commitText("X") }

        assertSame("commit/accessibility: the Error from the finder must come out as it went in", failure, thrown)
        assertEquals("commit/accessibility: the finder must have been asked exactly once", 1, asks)
    }

    @Test
    fun `an Error from any node member up to the text write leaves commitText and the node goes back once`() {
        // Each of these is reached through the outer catch, with the node already found.
        for (member in MEMBERS_UP_TO_THE_WRITE) {
            val rig = Rig(member)

            expectErrorLeaves("Error from " + member, rig)

            expectGivenBackOnceAndLast("Error from " + member, rig)
            assertEquals(
                "commit/accessibility: Error from " + member + ": the field must not look for the node a second time",
                1,
                rig.finder.findCalls,
            )
            assertEquals(
                "commit/accessibility: Error from " + member + ": no cursor may be placed after the Error",
                0,
                rig.inner.setSelectionCalls,
            )
        }
    }

    @Test
    fun `an Error from the cursor call leaves commitText after the text was set`() {
        // Reached only through the guard on the cursor call, which must catch an Exception and nothing wider.
        val rig = Rig(FakeFieldNode.SET_SELECTION)

        expectErrorLeaves("Error from setSelection", rig)

        assertEquals("commit/accessibility: the text must have been set once before the cursor call", 1, rig.inner.setTextCalls)
        assertEquals("commit/accessibility: the cursor call must have been made once", 1, rig.inner.setSelectionCalls)
        expectGivenBackOnceAndLast("Error from setSelection", rig)
    }

    @Test
    fun `an Error from release leaves commitText after an accepted insert`() {
        // Reached only through the guard on the release, which must catch an Exception and nothing wider.
        val rig = Rig(FakeFieldNode.RELEASE)

        expectErrorLeaves("Error from release after an insert", rig)

        assertEquals("commit/accessibility: the insert must have been made before the release", 1, rig.inner.setTextCalls)
        expectGivenBackOnceAndLast("Error from release after an insert", rig)
    }

    @Test
    fun `an Error from release leaves commitText after a refusal too`() {
        // A password field is turned away, and giving its node back then throws an Error.
        val rig = Rig(FakeFieldNode.RELEASE)
        rig.inner.isPassword = true

        expectErrorLeaves("Error from release after a refusal", rig)

        assertEquals("commit/accessibility: a password field must not be written to", 0, rig.inner.setTextCalls)
        assertEquals("commit/accessibility: a password field must not get a cursor", 0, rig.inner.setSelectionCalls)
        assertEquals("commit/accessibility: a password field's text must not be read", 0, rig.inner.textReads)
        expectGivenBackOnceAndLast("Error from release after a refusal", rig)
    }
}
