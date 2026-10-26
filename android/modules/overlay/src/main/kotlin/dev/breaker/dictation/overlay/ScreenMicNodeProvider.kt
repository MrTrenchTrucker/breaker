package dev.breaker.dictation.overlay

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * The accessibility provider for the app-screen mic view [ScreenMicView]: one host node plus, while recording,
 * two clickable virtual children.
 *
 * It sits beside the view, in its own file, so the view itself stays inside the module's 300-line cap. The
 * provider reaches into the view through the four members the view makes `internal` for it: [ScreenMicView.a11y],
 * [ScreenMicView.sendA11y], [ScreenMicView.a11yNodes] and the module's [BUTTON_CLASS].
 */
internal class ScreenMicNodeProvider(private val view: ScreenMicView) : AccessibilityNodeProvider() {

    override fun createAccessibilityNodeInfo(virtualId: Int): AccessibilityNodeInfo? =
        if (virtualId == HOST_VIEW_ID) hostNode() else nodeFor(virtualId)?.let { childNode(it) }

    /** The host node is the view itself: its usual fields, the button class name and a click action. */
    private fun hostNode() = AccessibilityNodeInfo.obtain(view).also { info ->
        view.onInitializeAccessibilityNodeInfo(info)
        info.className = BUTTON_CLASS
        info.text = view.contentDescription
        info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        for (node in view.a11yNodes()) info.addChild(view, node.id)
    }

    /** A child node: its label, its bounds on screen, a click and the focus action that fits its focus state. */
    private fun childNode(node: MicA11yNode): AccessibilityNodeInfo {
        val info = AccessibilityNodeInfo.obtain(view, node.id)
        info.setParent(view)
        info.className = BUTTON_CLASS
        info.text = node.label
        val onScreen = IntArray(2)
        view.getLocationOnScreen(onScreen)
        val b = node.bounds
        info.setBoundsInScreen(Rect(b.left + onScreen[0], b.top + onScreen[1], b.right + onScreen[0], b.bottom + onScreen[1]))
        info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        info.isAccessibilityFocused = view.a11y.focused == node.id
        info.addAction(if (info.isAccessibilityFocused) AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        info.isClickable = true
        return info
    }

    /** The virtual child with [virtualId], or null. */
    private fun nodeFor(virtualId: Int): MicA11yNode? = view.a11yNodes().firstOrNull { it.id == virtualId }

    /** A click runs the child's action; focus actions are tracked and reported; the host keeps the platform defaults. */
    override fun performAction(virtualId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualId == HOST_VIEW_ID) {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return view.performAccessibilityAction(action, arguments)
            ScreenMicProbeKit.hostAction(view)
            return true
        }
        val node = nodeFor(virtualId) ?: return false
        when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> MicAccessibility.run(node.action, view.onCancel, view.onSend)
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                val gains = action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                view.a11y.focus(virtualId, gains)
                view.sendA11y(virtualId, if (gains) AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED else AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
            }
            else -> return false
        }
        return true
    }
}
