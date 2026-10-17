package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * A hand-written [FieldNode] for tests.
 *
 * Its state is plain and settable. Every member counts its own calls in a separate
 * counter, and every call is also appended to one ordered list, so a test can say both
 * "this was read exactly once" and "this came before that". Reading the state through a
 * property counts; setting it through a property or [holdText] does not. The failure
 * switches make [refresh], [setText] and [setSelection] answer false or throw, and
 * [holdText] with null makes the text read return null.
 *
 * Each test builds its own node; nothing here is shared between tests. Pass the same
 * [calls] list to a [FakeFocusedNodeFinder] to see finder and node calls in one order.
 */
internal class FakeFieldNode(val calls: MutableList<String> = ArrayList()) : FieldNode {

    override var packageName: String? = "com.example.target"
        get() {
            packageNameReads++
            calls.add(READ_PACKAGE_NAME)
            return field
        }

    override var isPassword: Boolean = false
        get() {
            isPasswordReads++
            calls.add(READ_IS_PASSWORD)
            return field
        }

    override var isEditable: Boolean = true
        get() {
            isEditableReads++
            calls.add(READ_IS_EDITABLE)
            return field
        }

    override var isEnabled: Boolean = true
        get() {
            isEnabledReads++
            calls.add(READ_IS_ENABLED)
            return field
        }

    override var isShowingHint: Boolean = false
        get() {
            isShowingHintReads++
            calls.add(READ_IS_SHOWING_HINT)
            return field
        }

    override var maxTextLength: Int = -1
        get() {
            maxTextLengthReads++
            calls.add(READ_MAX_TEXT_LENGTH)
            return field
        }

    override var selectionStart: Int = -1
        get() {
            selectionStartReads++
            calls.add(READ_SELECTION_START)
            return field
        }

    override var selectionEnd: Int = -1
        get() {
            selectionEndReads++
            calls.add(READ_SELECTION_END)
            return field
        }

    private var heldText: String? = ""

    override val text: String?
        get() {
            textReads++
            calls.add(READ_TEXT)
            return heldText
        }

    /** What the field holds, set without counting as a read. Null makes the text read return null. */
    fun holdText(value: String?) {
        heldText = value
    }

    /** What [refresh] answers when it does not throw. */
    var refreshResult: Boolean = true

    /** When not null, [refresh] throws it. */
    var refreshFailure: RuntimeException? = null

    /** What [setText] answers when it does not throw. A true answer makes the field hold the new text. */
    var setTextResult: Boolean = true

    /** When not null, [setText] throws it, and the held text stays as it was. */
    var setTextFailure: RuntimeException? = null

    /** What [setSelection] answers when it does not throw. A true answer stores the new selection. */
    var setSelectionResult: Boolean = true

    /** When not null, [setSelection] throws it, and the selection stays as it was. */
    var setSelectionFailure: RuntimeException? = null

    var packageNameReads: Int = 0
        private set
    var isPasswordReads: Int = 0
        private set
    var isEditableReads: Int = 0
        private set
    var isEnabledReads: Int = 0
        private set
    var isShowingHintReads: Int = 0
        private set
    var maxTextLengthReads: Int = 0
        private set
    var textReads: Int = 0
        private set
    var selectionStartReads: Int = 0
        private set
    var selectionEndReads: Int = 0
        private set
    var refreshCalls: Int = 0
        private set
    var setTextCalls: Int = 0
        private set
    var setSelectionCalls: Int = 0
        private set
    var releaseCalls: Int = 0
        private set

    /** Every text [setText] was called with, in order, whether or not the call succeeded. */
    val setTextArguments: MutableList<String> = ArrayList()

    /** Every start and end [setSelection] was called with, in order, whether or not the call succeeded. */
    val setSelectionArguments: MutableList<Pair<Int, Int>> = ArrayList()

    /**
     * Every counter, in this order: package name, password, editable, enabled, hint,
     * max length, text, selection start, selection end, refresh, setText, setSelection, release.
     */
    fun counts(): List<Int> = listOf(
        packageNameReads,
        isPasswordReads,
        isEditableReads,
        isEnabledReads,
        isShowingHintReads,
        maxTextLengthReads,
        textReads,
        selectionStartReads,
        selectionEndReads,
        refreshCalls,
        setTextCalls,
        setSelectionCalls,
        releaseCalls,
    )

    override fun refresh(): Boolean {
        refreshCalls++
        calls.add(REFRESH)
        val failure: RuntimeException? = refreshFailure
        if (failure != null) throw failure
        return refreshResult
    }

    override fun setText(text: String): Boolean {
        setTextCalls++
        calls.add(SET_TEXT)
        setTextArguments.add(text)
        val failure: RuntimeException? = setTextFailure
        if (failure != null) throw failure
        if (setTextResult) heldText = text
        return setTextResult
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        setSelectionCalls++
        calls.add(SET_SELECTION)
        setSelectionArguments.add(Pair(start, end))
        val failure: RuntimeException? = setSelectionFailure
        if (failure != null) throw failure
        if (setSelectionResult) {
            selectionStart = start
            selectionEnd = end
        }
        return setSelectionResult
    }

    override fun release() {
        releaseCalls++
        calls.add(RELEASE)
    }

    /** The names written to [calls]; constants only, no state. */
    companion object {
        const val READ_PACKAGE_NAME: String = "packageName"
        const val READ_IS_PASSWORD: String = "isPassword"
        const val READ_IS_EDITABLE: String = "isEditable"
        const val READ_IS_ENABLED: String = "isEnabled"
        const val READ_IS_SHOWING_HINT: String = "isShowingHint"
        const val READ_MAX_TEXT_LENGTH: String = "maxTextLength"
        const val READ_TEXT: String = "text"
        const val READ_SELECTION_START: String = "selectionStart"
        const val READ_SELECTION_END: String = "selectionEnd"
        const val REFRESH: String = "refresh"
        const val SET_TEXT: String = "setText"
        const val SET_SELECTION: String = "setSelection"
        const val RELEASE: String = "release"
    }
}

/**
 * A [FocusedNodeFinder] that answers with a given node, or with null, and counts its calls.
 *
 * Pass the same list as the node's [FakeFieldNode.calls] to record finder and node calls
 * in one order.
 */
internal class FakeFocusedNodeFinder(
    var node: FieldNode?,
    private val calls: MutableList<String> = ArrayList(),
) : FocusedNodeFinder {

    var findCalls: Int = 0
        private set

    override fun findInputFocus(): FieldNode? {
        findCalls++
        calls.add(FIND_INPUT_FOCUS)
        return node
    }

    /** The name written to the shared call list; a constant, no state. */
    companion object {
        const val FIND_INPUT_FOCUS: String = "findInputFocus"
    }
}

/**
 * A [FieldState] with the usual field defaults: editable, enabled, not a password, not a
 * hint, no limit, and no selection. Every InsertPlan test builds its state with this.
 */
internal fun fieldState(
    text: String = "",
    selectionStart: Int = -1,
    selectionEnd: Int = -1,
    isShowingHint: Boolean = false,
    isPassword: Boolean = false,
    isEditable: Boolean = true,
    isEnabled: Boolean = true,
    maxTextLength: Int = -1,
): FieldState = FieldState(
    text = text,
    selectionStart = selectionStart,
    selectionEnd = selectionEnd,
    isShowingHint = isShowingHint,
    isPassword = isPassword,
    isEditable = isEditable,
    isEnabled = isEnabled,
    maxTextLength = maxTextLength,
)

/** Plan [dictated] into [state] and assert the whole outcome: an insert with exactly this new text and cursor. */
internal fun expectInserted(
    label: String,
    state: FieldState,
    dictated: String,
    expectedText: String,
    expectedCursor: Int,
) {
    val outcome: InsertOutcome = InsertPlan.plan(state, dictated)
    if (outcome !is Inserted) {
        fail("commit/accessibility: " + label + ": expected an insert but got " + outcome)
        return
    }
    assertEquals("commit/accessibility: " + label + ": wrong new text", expectedText, outcome.newText)
    assertEquals("commit/accessibility: " + label + ": wrong cursor", expectedCursor, outcome.cursor)
}

/** Plan [dictated] into [state] and assert the outcome is a refusal with exactly this reason. */
internal fun expectRefused(label: String, state: FieldState, dictated: String, expected: Refusal) {
    val outcome: InsertOutcome = InsertPlan.plan(state, dictated)
    if (outcome !is Refused) {
        fail("commit/accessibility: " + label + ": expected a refusal but got " + outcome)
        return
    }
    assertEquals("commit/accessibility: " + label + ": wrong refusal reason", expected, outcome.reason)
}
