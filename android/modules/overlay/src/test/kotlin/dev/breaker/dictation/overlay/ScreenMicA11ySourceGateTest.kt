package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source text checks for the sendA11y guard in ScreenMic.kt, which never runs on a plain JVM: with
 * accessibility off the platform throws IllegalStateException from requestSendAccessibilityEvent and
 * the app dies. Each rule runs first on a made-up sample that breaks it, which must be reported, and
 * on one that obeys it, which must stay quiet; only then on the real main source. Comments are
 * blanked and string literals are kept, as in the other source checks.
 */
class ScreenMicA11ySourceGateTest {

    private val file = "ScreenMic.kt"
    private val shouldSend = Regex("""ScreenMicA11y\s*\.\s*shouldSend\s*\(""")
    private val obtain = "AccessibilityEvent.obtain("

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** The sendA11y body, comments blanked, as the rules read it. */
    private fun sendA11yBody(source: String): String? = AdapterRules.blockBodyOf(SourceText.code(source), "sendA11y")

    /** The guard line: the line of the body that calls ScreenMicA11y.shouldSend, trimmed, or empty when there is none. */
    private fun guardLine(body: String): String =
        body.lines().drop(1).map { it.trim() }.firstOrNull { shouldSend.containsMatchIn(it) } ?: ""

    /** A failure here means sendA11y has no block body, or no line of it calls ScreenMicA11y.shouldSend. */
    private fun guardProblems(source: String): List<String> {
        val body = sendA11yBody(source) ?: return listOf("no sendA11y function with a block body was found")
        return if (guardLine(body).isEmpty()) listOf("the sendA11y guard does not call ScreenMicA11y.shouldSend(") else emptyList()
    }

    /** A failure here means the guard is not negated, so it would return while accessibility is on and send nothing. */
    private fun negationProblems(source: String): List<String> {
        val body = sendA11yBody(source) ?: return listOf("no sendA11y function with a block body was found")
        val line = guardLine(body)
        return when {
            !shouldSend.containsMatchIn(line) -> listOf("the sendA11y guard does not call ScreenMicA11y.shouldSend(")
            !line.contains("!ScreenMicA11y.shouldSend(") -> listOf("the sendA11y guard is not negated")
            else -> emptyList()
        }
    }

    /** A failure here means the guard does not read the accessibility manager's enabled state. */
    private fun enabledProblems(source: String): List<String> {
        val body = sendA11yBody(source) ?: return listOf("no sendA11y function with a block body was found")
        val line = guardLine(body)
        return when {
            !shouldSend.containsMatchIn(line) -> listOf("the sendA11y guard does not call ScreenMicA11y.shouldSend(")
            !line.contains(".isEnabled") -> listOf("the sendA11y guard does not read isEnabled")
            else -> emptyList()
        }
    }

    /** A failure here means the guard does not return early, so the event is still built and sent while accessibility is off. */
    private fun returnProblems(source: String): List<String> {
        val body = sendA11yBody(source) ?: return listOf("no sendA11y function with a block body was found")
        val line = guardLine(body)
        return when {
            !shouldSend.containsMatchIn(line) -> listOf("the sendA11y guard does not call ScreenMicA11y.shouldSend(")
            !line.endsWith("return") -> listOf("the sendA11y guard does not return")
            else -> emptyList()
        }
    }

    /** A failure here means the guard sits after AccessibilityEvent.obtain(, so the event is built (and the crash reached) while accessibility is off. */
    private fun orderProblems(source: String): List<String> {
        val body = sendA11yBody(source) ?: return listOf("no sendA11y function with a block body was found")
        val line = guardLine(body)
        if (!shouldSend.containsMatchIn(line)) return listOf("the sendA11y guard does not call ScreenMicA11y.shouldSend(")
        val guardAt = body.indexOf("ScreenMicA11y.shouldSend(")
        val obtainAt = body.indexOf(obtain)
        return when {
            obtainAt < 0 -> listOf("sendA11y does not build an AccessibilityEvent")
            guardAt > obtainAt -> listOf("the sendA11y guard is placed after AccessibilityEvent.obtain(")
            else -> emptyList()
        }
    }

    private val guarded = """private fun sendA11y(virtualId: Int, type: Int) {
        if (!ScreenMicA11y.shouldSend((context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isEnabled)) return
        val event = AccessibilityEvent.obtain(type)
        parent?.requestSendAccessibilityEvent(this, event)
    }"""

    /** A failure here means the sendA11y body carries a guard line that calls ScreenMicA11y.shouldSend and returns before AccessibilityEvent.obtain. */
    @Test
    fun `sendA11y guards its event before obtain`() {
        assertQuiet("the guarded body", guardProblems(guarded))
        assertFires("a body with no guard", guardProblems("private fun sendA11y(virtualId: Int, type: Int) {\n        val event = AccessibilityEvent.obtain(type)\n        parent?.requestSendAccessibilityEvent(this, event)\n    }"), "does not call ScreenMicA11y.shouldSend(")
        assertFires("a body with no sendA11y function", guardProblems("class A {\n    fun other() {}\n}"), "no sendA11y function")

        assertEquals("overlay: ScreenMic.kt must guard sendA11y before AccessibilityEvent.obtain", emptyList<String>(), guardProblems(inFile()))
    }

    /** A failure here means the guard is present but not negated, so it would refuse every event while accessibility is on. */
    @Test
    fun `the sendA11y guard is negated`() {
        assertQuiet("the negated guard", negationProblems(guarded))
        assertFires("an unnegated guard", negationProblems(guarded.replace("!ScreenMicA11y.shouldSend(", "ScreenMicA11y.shouldSend(")), "is not negated")

        assertEquals("overlay: ScreenMic.kt must negate the sendA11y guard", emptyList<String>(), negationProblems(inFile()))
    }

    /** A failure here means the guard does not read the accessibility manager's enabled state. */
    @Test
    fun `the sendA11y guard reads isEnabled`() {
        assertQuiet("the enabled guard", enabledProblems(guarded))
        assertFires("a guard with no enabled state", enabledProblems(guarded.replace(".isEnabled", ".isTouchExplorationEnabled")), "does not read isEnabled")

        assertEquals("overlay: ScreenMic.kt must read isEnabled in the sendA11y guard", emptyList<String>(), enabledProblems(inFile()))
    }

    /** A failure here means the guard does not return early. */
    @Test
    fun `the sendA11y guard returns`() {
        assertQuiet("the returning guard", returnProblems(guarded))
        assertFires("a guard that does not return", returnProblems(guarded.replace(")) return", ")")), "does not return")

        assertEquals("overlay: ScreenMic.kt must return from the sendA11y guard", emptyList<String>(), returnProblems(inFile()))
    }

    /** A failure here means the guard sits after AccessibilityEvent.obtain, so the event is built while accessibility is off. */
    @Test
    fun `the sendA11y guard sits before obtain`() {
        assertQuiet("the guard before obtain", orderProblems(guarded))
        assertFires("the guard after obtain", orderProblems("private fun sendA11y(virtualId: Int, type: Int) {\n        val event = AccessibilityEvent.obtain(type)\n        if (!ScreenMicA11y.shouldSend((context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isEnabled)) return\n        parent?.requestSendAccessibilityEvent(this, event)\n    }"), "placed after AccessibilityEvent.obtain(")

        assertEquals("overlay: ScreenMic.kt must place the sendA11y guard before AccessibilityEvent.obtain", emptyList<String>(), orderProblems(inFile()))
    }

    /** The real ScreenMic.kt from the main sources, or a failure naming the file when it is not there. */
    private fun inFile(): String =
        ModuleFiles.mainTexts()[file] ?: throw AssertionError("android_overlay: $file is missing from the main sources")
}
