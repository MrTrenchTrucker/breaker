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
 * On every window-relevant event it publishes one reading (the two presence booleans plus
 * the event name and time) through [KeyboardProbeReadings.publishLatest] on every such
 * event, so a release build reads the keyboard state of the phone. The probe path keeps
 * its own recent lines only while the probe is on;
 * it never reads text or content, and no new read of any text property appears here.
 */
class BreakerAccessibilityService : AccessibilityService() {

    private var published: AutoCloseable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        closePublished()
        published = FocusedFieldHolder.publish(NodeFocusedField(AndroidNodeFinder(this), packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A null event would NPE downstream, so drop it first — this is the production contract, not a probe guard.
        if (event == null) return
        // An event from Breaker's own package is Breaker's own UI reacting to itself: skip it.
        // Production read: publish the latest reading on every event, so a release build sees the keyboard state of the phone.
        if (!KeyboardProbeReadings.shouldProcessEvent(event.packageName?.toString(), packageName)) return
        KeyboardProbeReadings.publishLatest(
            fieldFocus = findFieldFocus(),
            imeWindow = hasInputMethodWindow(),
            lastEvent = AccessibilityEvent.eventTypeToString(event.eventType),
            time = System.nanoTime() / 1_000_000L,
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
     * keyboard). Needs the `flagRetrieveInteractiveWindows` config flag, which the service config sets.
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
