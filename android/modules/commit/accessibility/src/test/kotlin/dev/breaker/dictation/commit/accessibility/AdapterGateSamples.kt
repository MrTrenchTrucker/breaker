package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertTrue

/**
 * What the two device files may name: the allow-lists the adapter gate holds the real files to.
 * A new action, argument key, member, constant or import is a change to what the service may do
 * on the phone, so it is added here on purpose.
 */
internal object AdapterAllowed {
    val actions: Set<String> = setOf("ACTION_SET_TEXT", "ACTION_SET_SELECTION")
    val arguments: Set<String> = setOf(
        "ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE",
        "ACTION_ARGUMENT_SELECTION_START_INT",
        "ACTION_ARGUMENT_SELECTION_END_INT",
    )
    val members: Map<String, Set<String>> = mapOf(
        "node" to setOf(
            "packageName", "isPassword", "isEditable", "isEnabled", "isShowingHintText", "maxTextLength",
            "text", "textSelectionStart", "textSelectionEnd", "refresh", "performAction", "recycle",
        ),
        "root" to setOf("findFocus", "recycle"),
        "service" to setOf("rootInActiveWindow"),
        "found" to emptySet<String>(),
    )
    val constants: Set<String> = actions + arguments + "FOCUS_INPUT"
    val imports: Set<String> = setOf(
        "android.accessibilityservice.AccessibilityService",
        "android.content.Intent",
        "android.os.Bundle",
        "android.view.accessibility.AccessibilityEvent",
        "android.view.accessibility.AccessibilityNodeInfo",
        "dev.breaker.dictation.commit.accessibility.FieldNode",
        "dev.breaker.dictation.commit.accessibility.FocusedNodeFinder",
        "dev.breaker.dictation.commit.accessibility.NodeFocusedField",
        "dev.breaker.dictation.commit.adapter.FocusedFieldHolder",
    )
}

/** An edited copy of the sample adapter files, and the rules that copy must break. */
internal class AdapterVariant(val name: String, val expect: Set<String>, val node: String, val service: String)

/**
 * Sample copies of the two device files, written the way the real files are, and edited
 * copies of them that each break one thing the adapter gate guards.
 *
 * Every edit proves its target text is present, so a sample that no longer matches fails
 * on its own instead of quietly testing nothing.
 */
internal object AdapterGateSamples {

    val node: String = """
package dev.breaker.dictation.commit.accessibility.adapter

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.breaker.dictation.commit.accessibility.FieldNode
import dev.breaker.dictation.commit.accessibility.FocusedNodeFinder

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
        // needed on Android 11 and 12
        @Suppress("DEPRECATION")
        node.recycle()
    }
}

internal class AndroidNodeFinder(private val service: AccessibilityService) : FocusedNodeFinder {
    override fun findInputFocus(): FieldNode? {
        val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return null
        try {
            val found: AccessibilityNodeInfo = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            return AndroidFieldNode(found)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }
}
"""

    val service: String = """
package dev.breaker.dictation.commit.accessibility.adapter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.breaker.dictation.commit.accessibility.NodeFocusedField
import dev.breaker.dictation.commit.adapter.FocusedFieldHolder

class BreakerAccessibilityService : AccessibilityService() {

    private var published: AutoCloseable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        closePublished()
        published = FocusedFieldHolder.publish(NodeFocusedField(AndroidNodeFinder(this), packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // the event is never read
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

    private fun closePublished() {
        val handle: AutoCloseable? = published
        published = null
        try {
            handle?.close()
        } catch (e: Exception) {
            // nothing may escape
        }
    }
}
"""

    private const val PUBLISH_LINE: String =
        "        published = FocusedFieldHolder.publish(NodeFocusedField(AndroidNodeFinder(this), packageName))\n"
    private const val INTERRUPT_EMPTY: String = "    override fun onInterrupt() {\n    }"
    private const val TRY_OPEN: String = "        try {\n"
    private const val SUPPRESS: String = "        @Suppress(\"DEPRECATION\")\n"
    private const val REFRESH: String = "    override fun refresh(): Boolean = node.refresh()"

    private fun edit(source: String, from: String, to: String): String {
        assertTrue("commit/accessibility: an adapter gate sample edit found no target text: $from", source.contains(from))
        return source.replaceFirst(from, to)
    }

    private fun n(name: String, expect: Set<String>, from: String, to: String): AdapterVariant =
        AdapterVariant(name, expect, edit(node, from, to), service)

    private fun s(name: String, expect: Set<String>, from: String, to: String): AdapterVariant =
        AdapterVariant(name, expect, node, edit(service, from, to))

    private fun interrupt(name: String, expect: Set<String>, body: String): AdapterVariant =
        s(name, expect, INTERRUPT_EMPTY, "    override fun onInterrupt() {\n        $body\n    }")

    /** Each variant breaks one guarded thing; [AdapterVariant.expect] names the rules that must report it. */
    fun variants(): List<AdapterVariant> = listOf(
        n("a click action", setOf("ACTIONS", "PERFORM"), "ACTION_SET_SELECTION, arguments", "ACTION_CLICK, arguments"),
        n("a paste action", setOf("ACTIONS", "PERFORM"), "ACTION_SET_TEXT, arguments", "ACTION_PASTE, arguments"),
        n(
            "a paste next to the two actions",
            setOf("ACTIONS", "PERFORM"),
            REFRESH,
            "    override fun refresh(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_PASTE, null)",
        ),
        n("a number as the action", setOf("ACTIONS", "PERFORM"), "ACTION_SET_SELECTION, arguments", "131072, arguments"),
        interrupt("a global action", setOf("PERFORM", "INTERRUPT"), "performGlobalAction(1)"),
        interrupt("a screenshot", setOf("PERFORM", "INTERRUPT"), "takeScreenshot(0, null, null)"),
        interrupt("a gesture", setOf("PERFORM", "INTERRUPT"), "dispatchGesture(null, null, null)"),
        n("a string as a bundle key", setOf("KEYS"), "putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE", "putCharSequence(\"text\""),
        n(
            "a fourth bundle value",
            setOf("KEYS"),
            "        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)",
            "        arguments.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, \"x\")\n" +
                "        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)",
        ),
        n("accessibility focus", setOf("FOCUS", "MEMBERS"), "FOCUS_INPUT", "FOCUS_ACCESSIBILITY"),
        n("a second focus search", setOf("FOCUS"), TRY_OPEN, "        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)\n$TRY_OPEN"),
        n("a second root read", setOf("ROOT"), TRY_OPEN, "        service.rootInActiveWindow\n$TRY_OPEN"),
        n("a window list", setOf("ROOT", "WALK", "MEMBERS"), "service.rootInActiveWindow ?: return null", "service.windows.firstOrNull()?.root ?: return null"),
        n("a child walk", setOf("WALK", "MEMBERS"), "return AndroidFieldNode(found)", "return AndroidFieldNode(found.getChild(0))"),
        n("a parent read", setOf("WALK", "MEMBERS"), "return AndroidFieldNode(found)", "return AndroidFieldNode(found.parent)"),
        n("a search by text", setOf("WALK", "MEMBERS"), TRY_OPEN, "        root.findAccessibilityNodeInfosByText(\"x\")\n$TRY_OPEN"),
        n("a member nobody listed", setOf("MEMBERS"), "get() = node.isEditable", "get() = node.isEditable && node.isVisibleToUser"),
        s("a body in the event callback", setOf("EVENT"), "// the event is never read", "val kept = 1"),
        s("an event text read", setOf("EVENT", "EVENT_READ"), "// the event is never read", "val shown = event?.text"),
        s("an event source read", setOf("EVENT", "EVENT_READ"), "// the event is never read", "val shown = event?.source"),
        s("an event type read", setOf("EVENT", "EVENT_READ"), "// the event is never read", "val shown = event?.eventType"),
        s("an event passed on", setOf("EVENT", "EVENT_READ"), "// the event is never read", "val shown = event"),
        interrupt("a publish in the interrupt callback", setOf("PUBLISH", "INTERRUPT"), PUBLISH_LINE.trim()),
        AdapterVariant("a publish moved out of the connect callback", setOf("PUBLISH", "INTERRUPT"), node, edit(edit(service, PUBLISH_LINE, ""), INTERRUPT_EMPTY, "    override fun onInterrupt() {\n" + PUBLISH_LINE + "    }")),
        s("no close in the destroy callback", setOf("CLOSE"), "    override fun onDestroy() {\n        closePublished()\n", "    override fun onDestroy() {\n"),
        s("no close in the unbind callback", setOf("CLOSE"), "    override fun onUnbind(intent: Intent?): Boolean {\n        closePublished()\n", "    override fun onUnbind(intent: Intent?): Boolean {\n"),
        s("a close that catches everything", setOf("HELPER"), "catch (e: Exception)", "catch (e: Throwable)"),
        s("a close that catches a narrower type", setOf("HELPER"), "catch (e: Exception)", "catch (e: RuntimeException)"),
        s("a close that keeps the handle", setOf("HELPER"), "        published = null\n", ""),
        s("a publish before the close", setOf("ORDER"), "        closePublished()\n" + PUBLISH_LINE, PUBLISH_LINE + "        closePublished()\n"),
        s("no super call when connected", setOf("ORDER"), "        super.onServiceConnected()\n", ""),
        s("an unbind that does not return super", setOf("ORDER"), "return super.onUnbind(intent)", "return false"),
        n("no suppress on the node release", setOf("RECYCLE"), SUPPRESS + "        node.recycle()", "        node.recycle()"),
        n("a suppression of something else", setOf("RECYCLE"), SUPPRESS + "        node.recycle()", "        @Suppress(\"UNUSED\")\n        node.recycle()"),
        n(
            "a root recycled outside the finally",
            setOf("RECYCLE"),
            "        } finally {\n" + SUPPRESS.replace("        ", "            ") + "            root.recycle()\n        }",
            "        } finally {\n        }\n" + SUPPRESS + "        root.recycle()",
        ),
        n("a third suppress", setOf("RECYCLE"), REFRESH, "    override fun refresh(): Boolean {\n" + SUPPRESS + "        return node.refresh()\n    }"),
        n("a third recycle", setOf("RECYCLE"), REFRESH, "    override fun refresh(): Boolean {\n        node.recycle()\n        return node.refresh()\n    }"),
        interrupt("a recycle in the service", setOf("RECYCLE", "INTERRUPT"), "rootInActiveWindow?.recycle()"),
        s("an open class", setOf("CLASS"), "class BreakerAccessibilityService", "open class BreakerAccessibilityService"),
        s("an internal class", setOf("CLASS"), "class BreakerAccessibilityService", "internal class BreakerAccessibilityService"),
        s("an open member", setOf("CLASS"), "    private var published", "    open var published"),
        s("another class name", setOf("CLASS"), "class BreakerAccessibilityService", "class OtherService"),
        n("a public node class", setOf("INTERNAL"), "internal class AndroidFieldNode", "class AndroidFieldNode"),
        n("a log import", setOf("IMPORTS"), "import android.os.Bundle\n", "import android.os.Bundle\nimport android.util.Log\n"),
        n("a log call without an import", setOf("IMPORTS"), REFRESH, "    override fun refresh(): Boolean {\n        android.util.Log.d(\"t\", \"m\")\n        return node.refresh()\n    }"),
        n("a clipboard import", setOf("IMPORTS"), "import android.os.Bundle\n", "import android.os.Bundle\nimport android.content.ClipboardManager\n"),
        s(
            "an import of the parent registry",
            setOf("IMPORTS"),
            "import dev.breaker.dictation.commit.adapter.FocusedFieldHolder",
            "import dev.breaker.dictation.commit.FocusedFieldRegistry\nimport dev.breaker.dictation.commit.adapter.FocusedFieldHolder",
        ),
    )

    /** The sample files with forbidden names added in comments and string literals, which are not code. */
    fun withProse(): AdapterVariant = AdapterVariant(
        "forbidden names in comments and strings",
        emptySet(),
        node +
            "\n// ACTION_PASTE performGlobalAction getChild(0) windows findFocus(FOCUS_ACCESSIBILITY) recycle()\n" +
            "private const val NOTE: String = \"ACTION_CLICK @Suppress(\\\"DEPRECATION\\\") node.parent windows\"\n",
        service +
            "\n/* event.text getSource publish( FocusedFieldHolder.publish( open class abstract */\n" +
            "private const val NOTE: String = \"event.text publish( import android.util.Log\"\n",
    )
}
