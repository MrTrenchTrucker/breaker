package dev.breaker.dictation.ui.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The auth host view and its handover step, pinned as text: how the submit hands over the typed
 * values, that the password box is wiped even when the callback throws, and how a redraw leaves the
 * two text boxes alone. Each rule is a function over the source text that returns named
 * problems. It runs first on an edited copy of the real file, so a rule that cannot fire is
 * reported, and then on the real file, where it must be quiet.
 */
class AuthHostViewRulesTest {

    private val host = sourceOf("AuthHostView.kt")
    private val step = stepSource()

    private fun stepSource(): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/screen/auth/AuthSubmitStep.kt")
        assertTrue("render: auth host: the step source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun sourceOf(name: String): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/render", name)
        assertTrue("render: auth host: the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** [text] with comments removed and string literals blanked, and every run of spaces made one. */
    private fun codeOf(text: String): String =
        STRING_OR_COMMENT.replace(text) { m -> if (m.value.startsWith("/")) "" else "\"\"" }
            .replace(SPACES, " ")

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue(
            "render: auth host: $what must be reported with '$part', got $problems",
            problems.any { it.contains(part) },
        )

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: auth host: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        val hits = Regex(Regex.escape(old)).findAll(text).count()
        assertTrue("render: auth host: not exactly once: $old", hits == 1)
        return text.replace(old, new)
    }

    /** The code of the member that opens with [header], up to its first closing brace. */
    private fun bodyOf(code: String, header: String): String {
        val start = code.indexOf(header)
        return if (start < 0) "" else code.substring(start).substringBefore("}")
    }

    /** The named problems with the host in [text]; an empty list means every rule holds. */
    private fun hostProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        val submit = bodyOf(code, "private fun submitNow() {")
        val show = bodyOf(code, "private fun show(state: AuthState) {")
        val name = submit.indexOf("handler.username = userField.typedName()")
        val setsName = name >= 0 && name < submit.indexOf("submitThenWipe(")
        val onlyOne = count(code, "submitThenWipe(") == 1
        val takes = code.contains("submitThenWipe(passField.takeTyped(), handler::submit)")
        val wipes = code.contains("{ passField.wipeTyped() }")
        val direct = code.contains("handler.submit(")
        val fills = code.contains("fill(") || code.contains(NUL_LITERAL)
        val touches = show.contains("userField") || show.contains("passField")
        val addedOnce = count(code, "addView(userField") == 1 && count(code, "addView(passField") == 1
        return listOfNotNull(
            "the submit does not set the user name from the box before the handover".takeUnless { setsName },
            "the host does not hand over through exactly one submitThenWipe call".takeUnless { onlyOne },
            "the handover call does not take the password only from the box".takeUnless { takes },
            "the handover call does not wipe the password box".takeUnless { wipes },
            "the host hands the password to the handler directly".takeIf { direct },
            "the host fills an array it handed over".takeIf { fills },
            "the redraw touches a text box".takeIf { touches },
            "a text box is not added to the column exactly once".takeUnless { addedOnce },
        )
    }

    /** The named problems with the handover step in [text]; an empty list means every rule holds. */
    private fun stepProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        val tryAt = code.indexOf("try {")
        val finallyAt = code.indexOf(FINALLY)
        val afterFinally = if (finallyAt < 0) "" else code.substring(finallyAt + FINALLY.length)
        val inFinally = afterFinally.substringBefore("}")
        return listOfNotNull(
            "the step has no try before a finally".takeUnless { tryAt >= 0 && finallyAt > tryAt },
            "the finally does not run the wipe".takeUnless { inFinally.contains("wipeBox()") },
            "the step has a catch".takeIf { Regex("\\bcatch\\b").containsMatchIn(code) },
            "the step fills an array".takeIf { code.contains("fill(") },
        )
    }

    private fun count(code: String, part: String): Int = Regex(Regex.escape(part)).findAll(code).count()

    @Test
    fun `the real host meets every rule`() {
        assertQuiet("the real host", hostProblemsIn(host))
    }

    @Test
    fun `a submit that never sets the user name is reported`() {
        val old = "        handler.username = userField.typedName()\n"
        val missing = replaceOnce(host, old, "")
        assertFires("no user name", hostProblemsIn(missing), "does not set the user name")
    }

    @Test
    fun `a submit that hands over an empty array is reported`() {
        val empty = replaceOnce(host, "passField.takeTyped()", "CharArray(0)")
        assertFires("the old empty array", hostProblemsIn(empty), "only from the box")
    }

    @Test
    fun `a submit with an empty wipe is reported`() {
        val empty = replaceOnce(host, "{ passField.wipeTyped() }", "{ }")
        assertFires("no wipe", hostProblemsIn(empty), "does not wipe the password box")
    }

    @Test
    fun `a direct handover without the step is reported`() {
        val direct = replaceOnce(host, CALL, "handler.submit(passField.takeTyped())")
        assertFires("no step", hostProblemsIn(direct), "exactly one submitThenWipe")
        assertFires("a direct call", hostProblemsIn(direct), "directly")
    }

    @Test
    fun `a second handover call is reported`() {
        val twice = replaceOnce(host, CALL, "$CALL\n        $CALL")
        assertFires("two calls", hostProblemsIn(twice), "exactly one submitThenWipe")
    }

    @Test
    fun `a host that fills the array it handed over is reported`() {
        val filled = replaceOnce(host, CALL, "$CALL\n        passField.takeTyped().fill('" + "\\u0000" + "')")
        assertFires("a fill after the handover", hostProblemsIn(filled), "fills an array")
    }

    @Test
    fun `a redraw that touches a text box is reported`() {
        val old = "        val held = scrollY"
        val touched = replaceOnce(host, old, "        passField.wipeTyped()\n$old")
        assertFires("a wipe in the redraw", hostProblemsIn(touched), "redraw touches")
    }

    @Test
    fun `a host that does not add each text box to the column once is reported`() {
        val missing = replaceOnce(host, "        column.addView(passField, inputParams())\n", "")
        assertFires("a box never added", hostProblemsIn(missing), "exactly once")
    }

    @Test
    fun `the real handover step meets every rule`() {
        assertQuiet("the real step", stepProblemsIn(step))
    }

    @Test
    fun `a step that wipes only on a throw is reported`() {
        val caught = replaceOnce(step, "} finally {", "} catch (failure: Throwable) {")
        assertFires("a catch for a finally", stepProblemsIn(caught), "no try before a finally")
        assertFires("a catch word", stepProblemsIn(caught), "has a catch")
    }

    @Test
    fun `a step whose wipe follows the call and not a finally is reported`() {
        val old = "    try {\n        submit(typed)\n    } finally {\n        wipeBox()\n    }\n"
        val moved = replaceOnce(step, old, "    submit(typed)\n    wipeBox()\n")
        assertFires("a relocated wipe", stepProblemsIn(moved), "no try before a finally")
        assertFires("a relocated wipe", stepProblemsIn(moved), "does not run the wipe")
    }

    @Test
    fun `a step that fills the array in the finally is reported`() {
        val old = "        wipeBox()\n"
        val filled = replaceOnce(step, old, "        typed.fill('" + "\\u0000" + "')\n$old")
        assertFires("a fill in the finally", stepProblemsIn(filled), "fills an array")
    }

    private companion object {
        /** A comment, or a string literal as it is written. */
        val STRING_OR_COMMENT = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"")
        val SPACES = Regex("""\s+""")

        /** The one call that hands the password over, as the host writes it. */
        const val CALL = "submitThenWipe(passField.takeTyped(), handler::submit) { passField.wipeTyped() }"

        /** The opening of the finally block, as the step writes it. */
        const val FINALLY = "} finally {"

        /** The NUL character written as a character literal in code. */
        const val NUL_LITERAL = "'\\u0000'"
    }
}
