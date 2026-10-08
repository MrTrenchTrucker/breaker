package dev.breaker.dictation.commit.accessibility.adapter

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.breaker.dictation.commit.accessibility.FieldNode
import dev.breaker.dictation.commit.accessibility.FocusedNodeFinder

/**
 * One platform node seen as a [FieldNode]: every member is exactly one framework call.
 *
 * Only two actions are ever performed on it, setting the whole text and setting the
 * selection. It reads nothing beyond the members of [FieldNode], never walks to a
 * parent or a child, and never logs, stores or copies what it reads. The caller gives
 * the node back with [release] once.
 */
internal class AndroidFieldNode(private val node: AccessibilityNodeInfo) : FieldNode {

    override val packageName: String?
        get() = node.packageName?.toString()

    override val isPassword: Boolean
        get() = node.isPassword

    override val isEditable: Boolean
        get() = node.isEditable

    override val isEnabled: Boolean
        get() = node.isEnabled

    override val isShowingHint: Boolean
        get() = node.isShowingHintText

    override val maxTextLength: Int
        get() = node.maxTextLength

    override val text: String?
        get() = node.text?.toString()

    override val selectionStart: Int
        get() = node.textSelectionStart

    override val selectionEnd: Int
        get() = node.textSelectionEnd

    override fun refresh(): Boolean = node.refresh()

    override fun setText(text: String): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val arguments = Bundle()
        arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    override fun release() {
        // Android 11 and 12 need the node recycled; the call is deprecated and does nothing from Android 13.
        @Suppress("DEPRECATION")
        node.recycle()
    }
}

/**
 * Finds the field with input focus in the active window, at the moment it is asked.
 *
 * It looks at the root of the active window only and asks for the input-focused node
 * only: no list of windows, no walk over children or parents, no search by text or id.
 * The root is given back before it returns, and the node it finds is the caller's to
 * [AndroidFieldNode.release]. Nothing is kept and nothing is logged.
 */
internal class AndroidNodeFinder(private val service: AccessibilityService) : FocusedNodeFinder {

    override fun findInputFocus(): FieldNode? {
        val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return null
        try {
            val found: AccessibilityNodeInfo = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            return AndroidFieldNode(found)
        } finally {
            // Same as above: needed on Android 11 and 12, deprecated and a no-op from Android 13.
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }
}
