package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service as the adapter checks read it. The debug probe path adds one window-list read, one
 * publish into the probe holder, one guard, one package decision, two imports and the recycling of
 * the windows; each is removed here, and only here, so the older checks keep reading everything
 * else in the service.
 */
internal object ProbeView {
    const val HANDLER: String = "onAccessibilityEvent"
    const val FIELD_FUNCTION: String = "findFieldFocus"
    const val WINDOW_FUNCTION: String = "hasInputMethodWindow"
    const val PACKAGE_DECISION: String = "shouldProcessEvent"

    /** The event reads the probe path may make: the event's type and its package, and nothing else. */
    val allowedEventReads: Regex = Regex("""\bevent\s*\.\s*(eventType|packageName)\b""")

    /** The probe guard as the first statement of the handler, at the start of the handler text. */
    val guardStart: Regex = Regex("""^\s*if\s*\(\s*event\s*==\s*null\s*\|\|\s*!\s*KeyboardProbeReadings\s*\.\s*isEnabled\s*\(\s*\)\s*\)\s*return\b""")
    private val guard: Regex = Regex("""if\s*\(\s*event\s*==\s*null\s*\|\|\s*!\s*KeyboardProbeReadings\s*\.\s*isEnabled\s*\(\s*\)\s*\)\s*return""")
    private val decisionGuard: Regex = Regex(
        """if\s*\(\s*!\s*KeyboardProbeReadings\s*\.\s*shouldProcessEvent\s*\([^()]*(?:\([^()]*\)[^()]*)*\)\s*\)\s*return""",
    )
    val probePublish: Regex = Regex("""KeyboardProbeReadings\s*\.\s*publish\s*\(""")
    private val probeImport: Regex = Regex("""(?m)^\s*import\s+(android\.view\.accessibility\.AccessibilityWindowInfo|dev\.breaker\.dictation\.commit\.KeyboardProbeReadings)\s*$""")
    private val windowRead: Regex = Regex("""\bgetWindows\s*\(\s*\)|\bwindows\b|\brecycle\s*\(\s*\)""")

    /** The index just past the bracket that closes the one opened at [open]. */
    fun closeAfter(code: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < code.length) {
            val c: Char = code[index]
            if (c == '(' || c == '{') depth += 1
            if (c == ')' || c == '}') depth -= 1
            index += 1
            if (depth == 0) return index
        }
        return code.length
    }

    /** The text between the braces of the function [name] in [code], or null when it is missing. */
    fun body(code: String, name: String): String? {
        val head: MatchResult = Regex("""\bfun\s+""" + name + """\s*\(""").find(code) ?: return null
        val open: Int = code.indexOf('{', closeAfter(code, head.range.last))
        if (open < 0) return null
        val end: Int = closeAfter(code, open)
        if (end - 1 <= open) return null
        return code.substring(open + 1, end - 1)
    }

    /** [code] with the call that [call] starts removed, its arguments included. */
    fun withoutCall(code: String, call: Regex): String {
        val found: MatchResult = call.find(code) ?: return code
        val end: Int = closeAfter(code, found.range.last)
        return code.substring(0, found.range.first) + code.substring(end)
    }

    /** [code] with the declaration of the function [name] removed, its body included. */
    fun withoutFunction(code: String, name: String): String {
        val head: MatchResult = Regex("""(private\s+)?fun\s+""" + name + """\s*\(""").find(code) ?: return code
        val open: Int = code.indexOf('{', closeAfter(code, head.range.last))
        if (open < 0) return code
        return code.substring(0, head.range.first) + code.substring(closeAfter(code, open))
    }

    /** The probe's publish call inside [handler], as text; empty when the handler has none. */
    fun probeCall(handler: String): String {
        val found: MatchResult = probePublish.find(handler) ?: return ""
        return handler.substring(found.range.first, closeAfter(handler, found.range.last))
    }

    /** The service for the adapter checks: the probe guard, the package decision, the probe publish, the probe imports and the window-list reads removed. */
    fun adapter(code: String): String {
        val guarded: String = decisionGuard.replace(guard.replaceFirst(code, ""), "")
        val plain: String = probeImport.replace(withoutCall(guarded, probePublish), "")
        val window: String = body(plain, WINDOW_FUNCTION) ?: return plain
        if (window.isBlank()) return plain
        return plain.replace(window, windowRead.replace(window, ""))
    }

    /** The service for the member checks: the adapter view with the two probe functions taken out. */
    fun mapping(code: String): String = withoutFunction(withoutFunction(adapter(code), FIELD_FUNCTION), WINDOW_FUNCTION)
}

internal class ProbeReadGateTest {

    private val nodeFile: String = "adapter/AndroidFieldNode.kt"
    private val serviceFile: String = "adapter/BreakerAccessibilityService.kt"
    private val windowList: Regex = Regex("""\bgetWindows\s*\(""")
    private val recycleCall: Regex = Regex("""\brecycle\s*\(\s*\)""")
    private val publishCall: Regex = Regex("""\bpublish\s*\(""")
    private val windowType: Regex = Regex("""\bAccessibilityWindowInfo\b""")
    private val readsText: Regex = Regex("""\b(getText|text|getContentDescription|contentDescription|performAction)\b""")

    /** Any event member read outside the allowed two. */
    private val otherEventMembers: Regex = Regex("""\bevent\s*\.\s*\w+""")

    private fun real(path: String): String {
        val text: String = SourceFiles.mainSources()[path] ?: ""
        assertTrue("commit/accessibility: the probe check did not find " + path, text.isNotBlank())
        return text
    }

    private fun code(source: String): String = SourceFiles.strip(source).code

    private fun count(rule: Regex, code: String): Int = rule.findAll(code).count()

    private fun edit(source: String, from: String, to: String): String {
        assertTrue("commit/accessibility: the probe check sample has no text " + from, source.contains(from))
        return source.replaceFirst(from, to)
    }

    private fun windowReads(node: String, service: String): Int =
        count(windowList, node) + count(windowList, ProbeView.withoutFunction(service, ProbeView.WINDOW_FUNCTION))

    private fun publishesOutsideHandler(service: String): Int =
        count(publishCall, ProbeView.withoutFunction(service, ProbeView.HANDLER))

    private fun probeText(service: String): String =
        ProbeView.body(service, ProbeView.FIELD_FUNCTION).orEmpty() +
            ProbeView.body(service, ProbeView.WINDOW_FUNCTION).orEmpty() +
            ProbeView.probeCall(ProbeView.body(service, ProbeView.HANDLER).orEmpty())

    @Test
    fun `the handler returns before any read when the probe is off`() {
        val handler: String = ProbeView.body(code(real(serviceFile)), ProbeView.HANDLER).orEmpty()
        assertTrue("commit/accessibility: the event handler has no body", handler.isNotBlank())
        assertTrue(
            "commit/accessibility: the first statement of the event handler is not the probe guard",
            ProbeView.guardStart.containsMatchIn(handler),
        )
        val readFirst: String = "        val early = findFieldFocus()\n" + handler
        assertFalse(
            "commit/accessibility: a read before the probe guard was accepted",
            ProbeView.guardStart.containsMatchIn(readFirst),
        )
    }

    @Test
    fun `the service calls the package decision before any read`() {
        val handler: String = ProbeView.body(code(real(serviceFile)), ProbeView.HANDLER).orEmpty()
        assertTrue("commit/accessibility: the event handler has no body", handler.isNotBlank())
        val decision: Int = handler.indexOf(ProbeView.PACKAGE_DECISION)
        assertTrue(
            "commit/accessibility: the event handler does not call the package decision",
            decision >= 0,
        )
        val publish: Int = handler.indexOf("KeyboardProbeReadings.publish")
        assertTrue(
            "commit/accessibility: the package decision is not called before the publish",
            decision < publish,
        )
    }

    @Test
    fun `the probe reads the event package and the event type, and no other event member`() {
        val handler: String = ProbeView.body(code(real(serviceFile)), ProbeView.HANDLER).orEmpty()
        assertTrue(
            "commit/accessibility: the probe path does not read the event package",
            handler.contains("packageName"),
        )
        assertTrue(
            "commit/accessibility: the probe path does not read the event type",
            handler.contains("eventType"),
        )
        val other: String = handler.replace(ProbeView.allowedEventReads, "")
        assertEquals(
            "commit/accessibility: the probe path names an event member outside the allowed two",
            0,
            count(otherEventMembers, other),
        )
    }

    @Test
    fun `the window list is read in exactly one place`() {
        val service: String = code(real(serviceFile))
        assertEquals(
            "commit/accessibility: the service does not read the window list exactly once",
            1,
            count(windowList, service),
        )
        val inside: String = ProbeView.body(service, ProbeView.WINDOW_FUNCTION).orEmpty()
        assertEquals(
            "commit/accessibility: the one window-list read is not inside the probe function",
            1,
            count(windowList, inside),
        )
        val handler: String = ProbeView.body(service, ProbeView.HANDLER).orEmpty()
        assertTrue(
            "commit/accessibility: the probe function is not called from the handler after the guard",
            ProbeView.guardStart.containsMatchIn(handler) && handler.contains(ProbeView.WINDOW_FUNCTION + "()"),
        )
        assertEquals(
            "commit/accessibility: the window type is named outside its import and the probe function",
            1,
            count(windowType, ProbeView.withoutFunction(service, ProbeView.WINDOW_FUNCTION)),
        )
    }

    @Test
    fun `a window list read anywhere else is refused`() {
        val node: String = code(real(nodeFile))
        val service: String = code(real(serviceFile))
        assertEquals(
            "commit/accessibility: a window-list read was found outside the probe function",
            0,
            windowReads(node, service),
        )
        val nodeSample: String = code(real(nodeFile) + "\nprivate fun listed() = getWindows()\n")
        assertEquals("commit/accessibility: a window-list read in the node file was not refused", 1, windowReads(nodeSample, service))
        val serviceSample: String = code(edit(real(serviceFile), "override fun onInterrupt() {", "override fun onInterrupt() {\n        getWindows()"))
        assertEquals("commit/accessibility: a window-list read in the service was not refused", 1, windowReads(node, serviceSample))
        assertEquals("commit/accessibility: the two window-list reads were not both refused", 2, windowReads(nodeSample, serviceSample))
    }

    @Test
    fun `a second publish outside the probe path is refused`() {
        val node: String = code(real(nodeFile))
        val service: String = code(real(serviceFile))
        val handler: String = ProbeView.body(service, ProbeView.HANDLER).orEmpty()
        assertEquals(
            "commit/accessibility: the publish calls outside the probe path are not exactly the one holder publish",
            1,
            count(publishCall, node) + publishesOutsideHandler(service),
        )
        assertEquals("commit/accessibility: the probe path does not publish exactly once", 1, count(ProbeView.probePublish, handler))
        assertEquals("commit/accessibility: the handler publishes something other than the probe", 1, count(publishCall, handler))
        val sample: String = code(edit(real(serviceFile), "            handle?.close()", "            handle?.close()\n            publish(null)"))
        assertEquals(
            "commit/accessibility: a second publish outside the probe path was not refused",
            2,
            count(publishCall, node) + publishesOutsideHandler(sample),
        )
    }

    @Test
    fun `every window read from the list is recycled once`() {
        val raw: String = real(serviceFile)
        val window: String = ProbeView.body(code(raw), ProbeView.WINDOW_FUNCTION).orEmpty()
        assertEquals(
            "commit/accessibility: the window list is not recycled exactly once in the probe function",
            1,
            count(recycleCall, window),
        )
        assertTrue("commit/accessibility: the window recycle is not in the finally of the read", window.contains("finally"))
        val sample: String = code(edit(raw, "windows.forEach { it.recycle() }", ""))
        val sampleWindow: String = ProbeView.body(sample, ProbeView.WINDOW_FUNCTION).orEmpty()
        assertTrue(
            "commit/accessibility: a probe function without the window recycle was not refused",
            count(recycleCall, sampleWindow) != 1,
        )
    }

    @Test
    fun `the probe never reads text or content`() {
        val service: String = code(real(serviceFile))
        assertEquals(
            "commit/accessibility: the probe path names a text or content read",
            0,
            count(readsText, probeText(service)),
        )
        val sample: String = code(edit(real(serviceFile), "node.isEditable", "node.isEditable && node.text != null"))
        assertTrue(
            "commit/accessibility: a text read in the probe function was not refused",
            count(readsText, probeText(sample)) > 0,
        )
    }
}
