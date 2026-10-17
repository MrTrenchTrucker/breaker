package dev.breaker.dictation.commit.accessibility

/**
 * One text field of the screen, seen through the few things an insert needs.
 *
 * This is the seam between the insert logic and the platform's node type, so the logic
 * runs on a plain JVM. A node is only valid for a short time: it is read, used once and
 * given back. Nothing read from a node, above all [text], may be logged, stored, copied
 * or put into a message.
 */
internal interface FieldNode {
    /**
     * The package of the app that owns the field, or null when it is not known.
     * Used to refuse a field that belongs to this app. Never log or store it.
     */
    val packageName: String?

    /** True for a password field. An insert must refuse it. Never log or store it. */
    val isPassword: Boolean

    /** True when the field accepts typed text. Never log or store it. */
    val isEditable: Boolean

    /** True when the field is switched on. Never log or store it. */
    val isEnabled: Boolean

    /** True when [text] is the field's hint and not the user's text. Never log or store it. */
    val isShowingHint: Boolean

    /** The longest text the field accepts; negative means no limit. Never log or store it. */
    val maxTextLength: Int

    /**
     * The field's current text, or null when it has none.
     *
     * Reading it is the one read of the field's text, made only when an insert is
     * about to happen. The value is the user's own text: never log it, store it, copy
     * it or put it in a message.
     */
    val text: String?

    /** Where the selection starts, or -1 when there is none. Never log or store it. */
    val selectionStart: Int

    /** Where the selection ends, or -1 when there is none. Never log or store it. */
    val selectionEnd: Int

    /** Bring the node up to date; false when the node is no longer valid. */
    fun refresh(): Boolean

    /**
     * Replace the field's whole text with [text]; true when the field took it.
     * The value is the user's text: never log it, store it or put it in a message.
     */
    fun setText(text: String): Boolean

    /** Put the selection (or the cursor, when both are equal) at [start] to [end]; true when the field took it. */
    fun setSelection(start: Int, end: Int): Boolean

    /**
     * Give the node back to the platform. Whoever found the node calls this exactly once,
     * whatever happened to it in between.
     */
    fun release()
}

/**
 * Finds the field that has input focus, at the moment it is asked.
 *
 * The answer is not kept: the next call looks again. What is returned is a node that
 * the caller must give back with [FieldNode.release]. Nothing about it may be logged
 * or stored.
 */
internal fun interface FocusedNodeFinder {
    /** The editable field with input focus in the active window, or null when there is none. */
    fun findInputFocus(): FieldNode?
}
