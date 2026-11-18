package dev.breaker.dictation.ui.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The About host as a control, pinned as text, the way the other render source rules are.
 *
 * Each rule runs first on an edited copy of the real file, so a rule that cannot fire is
 * reported, and then on the real file. An edit fails by name when its text is not there
 * exactly once.
 */
class AboutHostViewRulesTest {

    private val root = File("src/main/kotlin/dev/breaker/dictation/ui")
    private val host = sourceOf("screen/about/AboutHostView.kt")

    /** The decision file, read only by the plain-Kotlin rule below. */
    private val decision: String by lazy { sourceOf("screen/about/AboutDecision.kt") }

    private fun sourceOf(relative: String): String {
        val file = File(root, relative)
        assertTrue("about host rules: the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** [text] with comments and string literals removed and whitespace collapsed to single spaces. */
    private fun codeOf(text: String): String =
        STRING_OR_COMMENT.replace(text) { m -> if (m.value.startsWith("/")) "" else "\"\"" }
            .replace(SPACES, " ")

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("about host rules: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("about host rules: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        val hits = Regex(Regex.escape(old)).findAll(text).count()
        assertTrue("about host rules: not exactly once: $old", hits == 1)
        return text.replace(old, new)
    }

    /** The named problems with the About host in [text]; an empty list means every rule holds. */
    private fun hostProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the host is not internal".takeUnless { code.contains("internal class AboutHostView(") },
            "the host is not a scroll view".takeUnless { code.contains(": ScrollView(context), AboutControl {") },
            "the host does not answer AboutControl".takeUnless { code.contains("AboutControl {") },
            "the host does not render the state it holds".takeUnless { code.contains("screen.render(state)") },
            "the host does not remove the child it drew before adding the new one"
                .takeUnless { code.indexOf("removeAllViews()") in 0 until code.indexOf("addView(") },
            "the host passes an empty intent lambda to the renderer".takeIf { code.contains("theme, {})") },
            "the host does not pass its own intent handler to the renderer"
                .takeUnless { code.contains("theme, ::onIntent)") },
            "show does not take the seven facts in the frozen order".takeUnless {
                code.contains("override fun show(admin: Boolean, updateAvailable: Boolean, installReady: Boolean, checking: Boolean, installing: Boolean, failed: Boolean, upToDate: Boolean)")
            },
            "show does not store the seven values in the frozen order".takeUnless {
                code.contains("state = AboutState(admin, updateAvailable, installReady, checking, installing, failed, upToDate)")
            },
            "refresh does not redraw from the held state".takeUnless { code.contains("override fun refresh() { draw() }") },
            "bind does not take exactly the two callbacks".takeUnless {
                code.contains("override fun bind(onCheck: () -> Unit, onInstall: () -> Unit) {")
            },
            "bind does not store the two callbacks".takeUnless {
                code.contains("this.onCheck = onCheck") && code.contains("this.onInstall = onInstall")
            },
            "the host does not ask AboutIntentHandler before it calls a callback".takeUnless {
                val asks = code.indexOf("AboutIntentHandler.accepts(intent.action, state)")
                asks >= 0 && asks < code.indexOf("-> onCheck()")
            },
        )
    }

    /** The named problems with the decision file in [text]: it stays plain Kotlin. */
    private fun decisionProblemsIn(text: String): List<String> =
        listOfNotNull(
            "the decision file names the framework".takeIf { Regex("""\bandroid\.""").containsMatchIn(codeOf(text)) },
        )

    @Test
    fun `the real About host meets every rule`() {
        assertQuiet("the real About host", hostProblemsIn(host))
    }

    @Test
    fun `a host that is not internal, not a scroll view or not a control is reported`() {
        val open = replaceOnce(host, "internal class AboutHostView(", "class AboutHostView(")
        assertFires("a public host", hostProblemsIn(open), "not internal")
        val plain = replaceOnce(host, ": ScrollView(context), AboutControl {", ": ScrollView(context) {")
        val plainProblems = hostProblemsIn(plain)
        assertFires("a host that is not a control", plainProblems, "does not answer AboutControl")
        assertFires("a host that is not a control", plainProblems, "not a scroll view")
    }

    @Test
    fun `a host that draws a fresh screen instead of the state it holds is reported`() {
        val fresh = replaceOnce(host, "screen.render(state)", "screen.render()")
        assertFires("a host that ignores its held state", hostProblemsIn(fresh), "does not render the state it holds")
    }

    @Test
    fun `a host that adds the new child before removing the old one is reported`() {
        val kept = replaceOnce(host, "removeAllViews()", "removeViewAt(0)")
        assertFires("a host that keeps the old child", hostProblemsIn(kept), "does not remove the child")
    }

    @Test
    fun `a host that passes an empty intent lambda is reported`() {
        val empty = replaceOnce(host, "theme, ::onIntent)", "theme, {})")
        val problems = hostProblemsIn(empty)
        assertFires("an empty intent lambda", problems, "empty intent lambda")
        assertFires("an empty intent lambda", problems, "does not pass its own intent handler")
    }

    @Test
    fun `a host whose show or refresh does not redraw is reported`() {
        val noStore = replaceOnce(
            host,
            "state = AboutState(admin, updateAvailable, installReady, checking, installing, failed, upToDate)",
            "state = AboutState(admin)",
        )
        assertFires("a show that stores one value", hostProblemsIn(noStore), "does not store the seven values")
        val noRefresh = replaceOnce(host, "override fun refresh() {\n        draw()\n    }", "override fun refresh() {\n    }")
        assertFires("a refresh that draws nothing", hostProblemsIn(noRefresh), "refresh does not redraw")
    }

    @Test
    fun `a host that calls a row callback without asking AboutIntentHandler is reported`() {
        val blind = replaceOnce(host, "if (!AboutIntentHandler.accepts(intent.action, state)) return\n", "")
        assertFires("a host that skips the guard", hostProblemsIn(blind), "does not ask AboutIntentHandler")
    }

    @Test
    fun `a host whose bind stores one callback only is reported`() {
        val noBind = replaceOnce(host, "this.onInstall = onInstall", "onInstall")
        assertFires("a bind that keeps one callback", hostProblemsIn(noBind), "bind does not store the two callbacks")
    }

    @Test
    fun `a host whose bind takes a third callback is reported`() {
        val third = replaceOnce(host, "override fun bind(onCheck: () -> Unit, onInstall: () -> Unit) {", "override fun bind(onCheck: () -> Unit, onInstall: () -> Unit, onExtra: () -> Unit) {")
        assertFires("a bind with a third callback", hostProblemsIn(third), "bind does not take exactly the two callbacks")
    }

    @Test
    fun `a host whose show takes the facts in another order is reported`() {
        val swapped = replaceOnce(host, "updateAvailable: Boolean, installReady: Boolean", "installReady: Boolean, updateAvailable: Boolean")
        assertFires("a show with the flags swapped", hostProblemsIn(swapped), "does not take the seven facts in the frozen order")
    }

    @Test
    fun `a host that stores the update flag and the install flag swapped is reported`() {
        val swapped = replaceOnce(host, "AboutState(admin, updateAvailable, installReady,", "AboutState(admin, installReady, updateAvailable,")
        assertFires("a mapping with the flags swapped", hostProblemsIn(swapped), "does not store the seven values")
    }

    @Test
    fun `a host that keeps the old eight value mapping is reported`() {
        val old = replaceOnce(
            host,
            "AboutState(admin, updateAvailable, installReady, checking, installing, failed, upToDate)",
            "AboutState(admin, updateAvailable, installReady, rollbackHeld, checking, installing, failed, upToDate)",
        )
        assertFires("the old eight value mapping", hostProblemsIn(old), "does not store the seven values")
    }

    @Test
    fun `a host that keeps the text from before the install flag is reported`() {
        val before = replaceOnce(
            host,
            "AboutState(admin, updateAvailable, installReady, checking, installing, failed, upToDate)",
            "AboutState(admin, offered, checking, installing, failed, upToDate)",
        )
        assertFires("the text from before the install flag", hostProblemsIn(before), "does not store the seven values")
    }

    @Test
    fun `harmless spacing in the host mapping is quiet`() {
        val spaced = replaceOnce(host, "state = AboutState(admin, updateAvailable, installReady,", "state =   AboutState(admin,\n            updateAvailable,   installReady,")
        assertQuiet("harmless spacing in the mapping", hostProblemsIn(spaced))
    }

    @Test
    fun `the decision file is plain Kotlin and the rule fires on a framework import`() {
        assertQuiet("the real decision file", decisionProblemsIn(decision))
        val plain = "package dev.breaker.dictation.ui.screen.about\n\ninternal data class AboutDecision(val rows: Set<String>, val sentence: String?)\n"
        assertQuiet("a plain decision file", decisionProblemsIn(plain))
        val framework = "package dev.breaker.dictation.ui.screen.about\n\nimport android.view.View\n"
        assertFires("a framework import", decisionProblemsIn(framework), "names the framework")
    }

    private companion object {
        /** A comment, or a string literal as it is written. */
        val STRING_OR_COMMENT = Regex("""//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\\n])*"""")
        val SPACES = Regex("""\s+""")
    }
}
