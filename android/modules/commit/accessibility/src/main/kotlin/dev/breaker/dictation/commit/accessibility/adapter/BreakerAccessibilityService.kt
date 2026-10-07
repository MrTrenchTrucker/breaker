package dev.breaker.dictation.commit.accessibility.adapter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.breaker.dictation.commit.accessibility.NodeFocusedField
import dev.breaker.dictation.commit.adapter.FocusedFieldHolder

/**
 * The accessibility service the system binds once the user turns it on. It exists to
 * put dictated text into the field the user is typing in, and to do nothing else.
 *
 * While it is connected it publishes one focused-field resolver through
 * [FocusedFieldHolder]; the resolver looks for the focused node only when the commit
 * service asks it to insert text on an explicit send. The service never reads an
 * event, never keeps a node or any text, and never acts on its own.
 */
class BreakerAccessibilityService : AccessibilityService() {

    private var published: AutoCloseable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        closePublished()
        published = FocusedFieldHolder.publish(NodeFocusedField(AndroidNodeFinder(this), packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The config lists one event type because the framework requires one; no event is ever read.
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
