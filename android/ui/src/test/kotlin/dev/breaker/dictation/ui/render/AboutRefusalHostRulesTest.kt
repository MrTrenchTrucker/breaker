package dev.breaker.dictation.ui.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The install refusal's source rules, on the real host, control and state text.
 *
 * Each rule runs on the real file and on a planted edit of it. A planted edit fails by name
 * when its text is not there exactly once, and each rule is shown to fire on its own sample,
 * so a rule that cannot fire is reported rather than passing quietly.
 */
class AboutRefusalHostRulesTest {

    private val root = File("src/main/kotlin/dev/breaker/dictation/ui")
    private val host = sourceOf("screen/about/AboutHostView.kt")
    private val control = sourceOf("AboutControl.kt")
    private val state = sourceOf("screen/about/AboutState.kt")

    private fun sourceOf(relative: String): String {
        val file = File(root, relative)
        assertTrue("refusal host rules: the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** [text] with comments and string literals removed and whitespace collapsed to single spaces. */
    private fun codeOf(text: String): String =
        STRING_OR_COMMENT.replace(text) { m -> if (m.value.startsWith("/")) "" else "\"\"" }
            .replace(SPACES, " ")

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        val hits = Regex(Regex.escape(old)).findAll(text).count()
        assertTrue("refusal host rules: not exactly once: $old", hits == 1)
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("refusal host rules: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("refusal host rules: $what must not be reported", emptyList<String>(), problems)

    /** The named problems with the host's refusal path: a setter that stores alone, and a show that keeps the held flag. */
    private fun hostProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        val setterStart = code.indexOf("override fun setInstallRefused")
        val setterEnd = if (setterStart < 0) -1 else code.indexOf("}", setterStart)
        val setterBody = when {
            setterStart < 0 -> ""
            setterEnd < 0 -> code.substring(setterStart)
            else -> code.substring(setterStart, setterEnd + 1)
        }
        return listOfNotNull(
            "the host has no setter that stores the refusal alone"
                .takeUnless { code.contains("override fun setInstallRefused(refused: Boolean) { state = state.copy(installRefused = refused) }") },
            "the setter draws the screen".takeIf { setterBody.contains("draw()") },
            "show does not keep the held refusal".takeUnless {
                code.contains("state = AboutState(admin, updateAvailable, installReady, checking, installing, failed, upToDate).copy(installRefused = state.installRefused)")
            },
        )
    }

    /** The named problems with the control: the seven facts of show unchanged, and the setter with a default empty body. */
    private fun controlProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "show does not keep the seven facts in the frozen order".takeUnless {
                code.contains("fun show(admin: Boolean, updateAvailable: Boolean, installReady: Boolean, checking: Boolean, installing: Boolean, failed: Boolean, upToDate: Boolean)")
            },
            "the setter has no default empty body".takeUnless { code.contains("fun setInstallRefused(refused: Boolean) {}") },
        )
    }

    /** The named problems with the state: the refusal field defaults to false and comes last. */
    private fun stateProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the state has no installRefused field defaulting to false".takeUnless { code.contains("val installRefused: Boolean = false,") },
            "the installRefused field is not last".takeUnless { code.contains("val upToDate: Boolean = false, val installRefused: Boolean = false, )") },
        )
    }

    @Test
    fun `the real host control and state meet every refusal rule`() {
        assertQuiet("the real host", hostProblemsIn(host))
        assertQuiet("the real control", controlProblemsIn(control))
        assertQuiet("the real state", stateProblemsIn(state))
    }

    @Test
    fun `a setter that draws the screen is reported`() {
        val drawing = replaceOnce(host, "        state = state.copy(installRefused = refused)\n    }", "        state = state.copy(installRefused = refused)\n        draw()\n    }")
        assertFires("a setter that draws", hostProblemsIn(drawing), "the setter draws the screen")
    }

    @Test
    fun `a setter that stores another field is reported`() {
        val wrong = replaceOnce(host, "state = state.copy(installRefused = refused)", "state = state.copy(failed = refused)")
        assertFires("a setter that stores another field", hostProblemsIn(wrong), "the host has no setter that stores the refusal alone")
    }

    @Test
    fun `a show that drops the held refusal is reported`() {
        val dropped = replaceOnce(host, ".copy(installRefused = state.installRefused)", "")
        assertFires("a show that drops the held refusal", hostProblemsIn(dropped), "show does not keep the held refusal")
    }

    @Test
    fun `harmless spacing in the setter is quiet`() {
        val spaced = replaceOnce(host, "state = state.copy(installRefused = refused)", "state =   state.copy(installRefused =\n            refused)")
        assertQuiet("harmless spacing in the setter", hostProblemsIn(spaced))
    }

    @Test
    fun `a control whose setter has a body is reported`() {
        val bodied = replaceOnce(control, "fun setInstallRefused(refused: Boolean) {}", "fun setInstallRefused(refused: Boolean) { Unit }")
        assertFires("a setter with a body", controlProblemsIn(bodied), "the setter has no default empty body")
    }

    @Test
    fun `a control whose show takes the facts in another order is reported`() {
        val swapped = replaceOnce(control, "failed: Boolean, upToDate: Boolean)", "upToDate: Boolean, failed: Boolean)")
        assertFires("a control show with the facts swapped", controlProblemsIn(swapped), "show does not keep the seven facts")
    }

    @Test
    fun `a state whose refusal field comes before upToDate or has no default is reported`() {
        val early = "internal data class AboutState(\n    val installRefused: Boolean = false,\n    val upToDate: Boolean = false,\n)\n"
        assertFires("a refusal field before upToDate", stateProblemsIn(early), "not last")
        val noDefault = replaceOnce(state, "val installRefused: Boolean = false,", "val installRefused: Boolean,")
        assertFires("a refusal field with no default", stateProblemsIn(noDefault), "no installRefused field defaulting to false")
    }

    private companion object {
        /** A comment, or a string literal as it is written. */
        val STRING_OR_COMMENT = Regex("""//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\\n])*"""")
        val SPACES = Regex("""\s+""")
    }
}
