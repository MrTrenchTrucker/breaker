package dev.breaker.dictation.commit.accessibility.adapter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.breaker.dictation.commit.KeyboardProbeReadings
import dev.breaker.dictation.commit.accessibility.NodeFocusedField
import dev.breaker.dictation.commit.adapter.FocusedFieldHolder

/**
 * The accessibility service the system binds once the user turns it on. It exists to
 * put dictated text into the field the user is typing in.
 *
 * While it is connected it publishes one focused-field resolver through
 * [FocusedFieldHolder]; the resolver looks for the focused node only when the commit
 * service asks it to insert text on an explicit send. The commit path never reads an
 * event, never keeps a node or any text, and never acts on its own.
 *
 * A debug probe path sits beside it and is off by default. When the probe is on, each
 * window event records two readings into [KeyboardProbeReadings]: whether the focused
 * field of the active window is editable, and whether a keyboard window is open. The
 * probe never reads text or content.
 */
class BreakerAccessibilityService : AccessibilityService() {

    private var published: AutoCloseable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        closePublished()
        published = FocusedFieldHolder.publish(NodeFocusedField(AndroidNodeFinder(this), packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Probe path only: with the probe off, this returns before any read.
        if (event == null || !KeyboardProbeReadings.isEnabled()) return
        KeyboardProbeReadings.publish(
            fieldFocus = findFieldFocus(),
            imeWindow = hasInputMethodWindow(),
            lastEvent = AccessibilityEvent.eventTypeToString(event.eventType),
            time = System.currentTimeMillis(),
        )
    }

    /**
     * The field-focus signal: true when the active window's input-focused node is editable. The
     * active window is the app the user is in (the other app when Breaker is in the background), so this
     * sees that app's field, not Breaker's. The node is released exactly once, whatever the read
     * returns.
     */
    private fun findFieldFocus(): Boolean {
        val node = AndroidNodeFinder(this).findInputFocus() ?: return false
        return try {
            node.isEditable
        } finally {
            node.release()
        }
    }

    /**
     * The keyboard-window signal: true when the window list carries an input-method window (the
     * keyboard). Needs the `flagRetrieveInteractiveWindows` config flag, which the debug build sets.
     * Each window in the list is recycled once.
     */
    private fun hasInputMethodWindow(): Boolean {
        val windows = getWindows()
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        closePublished()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        closePublished()
        super.onDestroy()
    }

    /** Clears the published resolver; the handle is dropped first so a failing close cannot leave it behind. */
    private fun closePublished() {
        val handle: AutoCloseable? = published
        published = null
        try {
            handle?.close()
        } catch (e: Exception) {
            // Nothing may escape from a lifecycle callback, and there is nothing to report.
        }
    }
}
