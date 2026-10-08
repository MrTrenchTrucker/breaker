package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertTrue

/**
 * The exact declaration the mapping gate requires for each member of the two device classes and
 * of the service class.
 *
 * Whitespace is not significant (the gate squashes both sides), everything else is: the
 * member kind, the type, the framework member read, the action performed and the value put
 * under each bundle key. A change here is a change to what the field flags and actions mean
 * on the phone, so it is made on purpose.
 */
internal object MappingExpected {
    const val NODE_HEADER: String = "internal class AndroidFieldNode(private val node: AccessibilityNodeInfo) : FieldNode"
    const val FINDER_HEADER: String = "internal class AndroidNodeFinder(private val service: AccessibilityService) : FocusedNodeFinder"
    const val SERVICE_HEADER: String = "class BreakerAccessibilityService : AccessibilityService()"

    val node: Map<String, String> = mapOf(
        "packageName" to "override val packageName: String? get() = node.packageName?.toString()",
        "isPassword" to "override val isPassword: Boolean get() = node.isPassword",
        "isEditable" to "override val isEditable: Boolean get() = node.isEditable",
        "isEnabled" to "override val isEnabled: Boolean get() = node.isEnabled",
        "isShowingHint" to "override val isShowingHint: Boolean get() = node.isShowingHintText",
        "maxTextLength" to "override val maxTextLength: Int get() = node.maxTextLength",
        "text" to "override val text: String? get() = node.text?.toString()",
        "selectionStart" to "override val selectionStart: Int get() = node.textSelectionStart",
        "selectionEnd" to "override val selectionEnd: Int get() = node.textSelectionEnd",
        "refresh" to "override fun refresh(): Boolean = node.refresh()",
        "setText" to """
            override fun setText(text: String): Boolean {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
        """,
        "setSelection" to """
            override fun setSelection(start: Int, end: Int): Boolean {
                val arguments = Bundle()
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
            }
        """,
        "release" to "override fun release() { @Suppress(\"\") node.recycle() }",
    )

    val finder: Map<String, String> = mapOf(
        "findInputFocus" to """
            override fun findInputFocus(): FieldNode? {
                val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return null
                try {
                    val found: AccessibilityNodeInfo = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
                    return AndroidFieldNode(found)
                } finally {
                    @Suppress("") root.recycle()
                }
            }
        """,
    )

    val service: Map<String, String> = mapOf(
        "published" to "private var published: AutoCloseable? = null",
        "onServiceConnected" to """
            override fun onServiceConnected() {
                super.onServiceConnected()
                closePublished()
                published = FocusedFieldHolder.publish(NodeFocusedField(AndroidNodeFinder(this), packageName))
            }
        """,
        "onAccessibilityEvent" to "override fun onAccessibilityEvent(event: AccessibilityEvent?) { }",
        "onInterrupt" to "override fun onInterrupt() { }",
        "onUnbind" to """
            override fun onUnbind(intent: Intent?): Boolean {
                closePublished()
                return super.onUnbind(intent)
            }
        """,
        "onDestroy" to """
            override fun onDestroy() {
                closePublished()
                super.onDestroy()
            }
        """,
        "closePublished" to """
            private fun closePublished() {
                val handle: AutoCloseable? = published
                published = null
                try {
                    handle?.close()
                } catch (e: Exception) {
                }
            }
        """,
    )
}

/** An edited copy of the real node file and the exact set of problems the mapping gate must report for it. */
internal class MappingVariant(val name: String, val expect: Set<String>, val text: String)

/** Edited copies of the real device node file, each breaking exactly one mapping the gate pins. */
internal object MappingSamples {

    /** [source] with the first [from] replaced by [to]; fails when there is nothing to replace. */
    fun edit(source: String, from: String, to: String): String {
        assertTrue("commit/accessibility: a mapping gate sample edit found no target text: " + from, source.contains(from))
        assertTrue("commit/accessibility: a mapping gate sample edit is no change: " + from, from != to)
        return source.replaceFirst(from, to)
    }

    private fun getter(name: String, type: String, expr: String): String =
        "override val " + name + ": " + type + "\n        get() = " + expr

    private fun swap(name: String, type: String, from: String, replacement: String): Pair<String, String> =
        getter(name, type, from) to getter(name, type, replacement)

    fun variant(real: String, name: String, expect: Set<String>, vararg edits: Pair<String, String>): MappingVariant {
        var text: String = real
        for (pair in edits) {
            text = edit(text, pair.first, pair.second)
        }
        return MappingVariant(name, expect, text)
    }

    private const val SET_TEXT_CALL: String = "node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)"
    private const val SET_SELECTION_CALL: String = "node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)"
    private const val START_KEY: String = "ACTION_ARGUMENT_SELECTION_START_INT, start)"
    private const val END_KEY: String = "ACTION_ARGUMENT_SELECTION_END_INT, end)"
    private const val REFRESH_LINE: String = "    override fun refresh(): Boolean = node.refresh()\n\n"
    private const val FIND_FOCUS: String = "root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)"

    /** Copies that break one mapping each (or the node file's shape). */
    fun firing(real: String): List<MappingVariant> = listOf(
        variant(real, "the password flag is always false", setOf("isPassword"), swap("isPassword", "Boolean", "node.isPassword", "false")),
        variant(real, "the password flag reads the editable flag", setOf("isPassword"), swap("isPassword", "Boolean", "node.isPassword", "node.isEditable")),
        variant(real, "the password flag needs a second flag", setOf("isPassword"), swap("isPassword", "Boolean", "node.isPassword", "node.isPassword && node.isEnabled")),
        variant(real, "the editable flag is always true", setOf("isEditable"), swap("isEditable", "Boolean", "node.isEditable", "true")),
        variant(real, "the enabled flag is always true", setOf("isEnabled"), swap("isEnabled", "Boolean", "node.isEnabled", "true")),
        variant(real, "the enabled flag is read in another letter case", setOf("isEnabled"), swap("isEnabled", "Boolean", "node.isEnabled", "node.isenabled")),
        variant(real, "the hint flag is always false", setOf("isShowingHint"), swap("isShowingHint", "Boolean", "node.isShowingHintText", "false")),
        variant(real, "the length limit is always minus one", setOf("maxTextLength"), swap("maxTextLength", "Int", "node.maxTextLength", "-1")),
        variant(real, "the text is read from the package name", setOf("text"), swap("text", "String?", "node.text?.toString()", "node.packageName?.toString()")),
        variant(real, "the package name is always null", setOf("packageName"), swap("packageName", "String?", "node.packageName?.toString()", "null")),
        variant(real, "the selection start is a constant", setOf("selectionStart"), swap("selectionStart", "Int", "node.textSelectionStart", "0")),
        variant(
            real, "selection start and end are swapped", setOf("selectionStart", "selectionEnd"),
            swap("selectionStart", "Int", "node.textSelectionStart", "node.textSelectionEnd"),
            swap("selectionEnd", "Int", "node.textSelectionEnd", "node.textSelectionStart"),
        ),
        variant(real, "refresh always answers true", setOf("refresh"), "Boolean = node.refresh()" to "Boolean = true"),
        variant(real, "setText performs the selection action", setOf("setText"), SET_TEXT_CALL to SET_SELECTION_CALL),
        variant(real, "setSelection performs the text action", setOf("setSelection"), SET_SELECTION_CALL to SET_TEXT_CALL),
        variant(real, "setText sends a fixed text", setOf("setText"), "ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)" to "ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, \"x\")"),
        variant(real, "setText sends no bundle", setOf("setText"), "ACTION_SET_TEXT, arguments)" to "ACTION_SET_TEXT, Bundle())"),
        variant(real, "the selection keys hold each other's values", setOf("setSelection"), START_KEY to "ACTION_ARGUMENT_SELECTION_START_INT, end)", END_KEY to "ACTION_ARGUMENT_SELECTION_END_INT, start)"),
        variant(real, "release does not recycle the node", setOf("release"), "node.recycle()" to "node.refresh()"),
        variant(real, "a member is missing", setOf("refresh (missing)"), REFRESH_LINE to ""),
        variant(
            real, "a member is added", setOf("leak"),
            "    override fun release() {" to "    fun leak(): Boolean = " + SET_TEXT_CALL + "\n\n    override fun release() {",
        ),
        variant(real, "the node class takes a public property", setOf("AndroidFieldNode header"), "(private val node: AccessibilityNodeInfo)" to "(val node: AccessibilityNodeInfo)"),
        variant(real, "the finder asks for accessibility focus", setOf("findInputFocus"), FIND_FOCUS to "root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)"),
        variant(real, "the finder does not wrap the node it found", setOf("findInputFocus"), "return AndroidFieldNode(found)" to "return null"),
        variant(real, "the finder takes its root from elsewhere", setOf("findInputFocus"), "service.rootInActiveWindow" to "service.getRoot()"),
        variant(real, "the finder does not give the root back", setOf("findInputFocus"), "root.recycle()" to "root.refresh()"),
        MappingVariant("a stray top level function", setOf("top level"), real + "\ninternal fun other(): Int = 1\n"),
    )

    /** Copies that must stay quiet: only layout and comments differ from the real file. */
    fun quiet(real: String): List<MappingVariant> = listOf(
        MappingVariant("the real file", emptySet(), real),
        variant(
            real, "comments and line breaks inside a getter", emptySet(),
            "get() = node.isPassword" to "get() =\n            /* the flag */ node.isPassword // read once",
        ),
    )
}

/** Edited copies of the real service file, each breaking one pinned member (or the class shape). */
internal object ServiceSamples {
    private const val PUBLISH: String = "published = FocusedFieldHolder.publish(NodeFocusedField(AndroidNodeFinder(this), packageName))"
    private const val EVENT_COMMENT: String = "no event is ever read.\n"
    private const val INTERRUPT: String = "    override fun onInterrupt() {\n    }\n\n"
    private const val CONNECTED: String = "super.onServiceConnected()\n        closePublished()\n"
    private const val UNBIND: String = "Boolean {\n        closePublished()\n"
    private const val DESTROY: String = "override fun onDestroy() {\n        closePublished()\n"
    private const val UNPUBLISH: String = "        published = null\n"

    private fun variant(real: String, name: String, expect: Set<String>, vararg edits: Pair<String, String>): MappingVariant =
        MappingSamples.variant(real, name, expect, *edits)

    /** Copies that break one pinned member each. */
    fun firing(real: String): List<MappingVariant> = listOf(
        variant(real, "the publish line passes an empty package name", setOf("onServiceConnected"), "AndroidNodeFinder(this), packageName)" to "AndroidNodeFinder(this), \"\")"),
        variant(real, "the published handle is discarded", setOf("onServiceConnected"), PUBLISH to PUBLISH.removePrefix("published = ")),
        variant(real, "the publish line moves out of the connect callback", setOf("onServiceConnected", "onAccessibilityEvent"), "        " + PUBLISH + "\n" to "", EVENT_COMMENT to EVENT_COMMENT + "        " + PUBLISH + "\n"),
        variant(real, "the connect callback does not clear an earlier handle", setOf("onServiceConnected"), CONNECTED to "super.onServiceConnected()\n"),
        variant(real, "the connect callback skips the base call", setOf("onServiceConnected"), "        super.onServiceConnected()\n" to ""),
        variant(real, "the handle property is public", setOf("published"), "private var published" to "var published"),
        variant(real, "the handle property starts with a value", setOf("published"), "AutoCloseable? = null" to "AutoCloseable? = AutoCloseable { }"),
        variant(real, "the event callback gets a body", setOf("onAccessibilityEvent"), EVENT_COMMENT to EVENT_COMMENT + "        closePublished()\n"),
        variant(real, "the event callback reads the event", setOf("onAccessibilityEvent"), EVENT_COMMENT to EVENT_COMMENT + "        event?.eventType\n"),
        variant(real, "the interrupt callback gets a body", setOf("onInterrupt"), "override fun onInterrupt() {\n" to "override fun onInterrupt() {\n        closePublished()\n"),
        variant(real, "the unbind callback does not close", setOf("onUnbind"), UNBIND to "Boolean {\n"),
        variant(real, "the unbind callback answers false", setOf("onUnbind"), "return super.onUnbind(intent)" to "return false"),
        variant(real, "the destroy callback does not close", setOf("onDestroy"), DESTROY to "override fun onDestroy() {\n"),
        variant(real, "the destroy callback skips the base call", setOf("onDestroy"), "        super.onDestroy()\n" to ""),
        variant(real, "the close keeps the handle in the property", setOf("closePublished"), UNPUBLISH to ""),
        variant(real, "the close reads the property twice", setOf("closePublished"), "handle?.close()" to "published?.close()"),
        variant(real, "the close drops a null into the handle", setOf("closePublished"), "AutoCloseable? = published" to "AutoCloseable? = null"),
        variant(real, "the close calls the member in another letter case", setOf("closePublished"), "handle?.close()" to "handle?.Close()"),
        variant(real, "the close catches everything", setOf("closePublished"), "catch (e: Exception)" to "catch (e: Throwable)"),
        variant(real, "the class is open", setOf("BreakerAccessibilityService header"), "class BreakerAccessibilityService : " to "open class BreakerAccessibilityService : "),
        variant(real, "the class is internal", setOf("BreakerAccessibilityService header"), "class BreakerAccessibilityService : " to "internal class BreakerAccessibilityService : "),
        variant(real, "a second member is added", setOf("leak"), "    /** Clears the published" to "    fun leak(): Int = 1\n\n    /** Clears the published"),
        variant(real, "a member is missing", setOf("onInterrupt (missing)"), INTERRUPT to ""),
        variant(real, "a member is repeated", setOf("onInterrupt (repeated)"), INTERRUPT to INTERRUPT + INTERRUPT),
        MappingVariant("a stray top level function", setOf("top level"), real + "\ninternal fun other(): Int = 1\n"),
    )

    /** Copies that must stay quiet: only comments and layout differ from the real file. */
    fun quiet(real: String): List<MappingVariant> = listOf(
        MappingVariant("the real file", emptySet(), real),
        variant(
            real, "comments and a line break inside bodies", emptySet(),
            "closePublished()\n        super.onDestroy()" to "closePublished() // drop the handle\n        /* then the base */\n        super\n            .onDestroy()",
            "FocusedFieldHolder.publish(NodeFocusedField(" to "FocusedFieldHolder.publish(\n            NodeFocusedField(",
        ),
    )
}
