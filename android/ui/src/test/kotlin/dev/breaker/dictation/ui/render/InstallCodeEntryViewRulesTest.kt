package dev.breaker.dictation.ui.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The install code box, pinned as text, the way the other render source rules are. Each rule is a
 * function over a source text that returns named problems. It runs first on an edited copy of the
 * real file, so a rule that cannot fire is reported, and then on the real file, where it must be
 * quiet. An edit fails by name when its text is not there exactly once.
 */
class InstallCodeEntryViewRulesTest {

    private val root = File("src/main/kotlin/dev/breaker/dictation/ui/render")
    private val box = sourceOf("InstallCodeEntryView.kt")

    private fun sourceOf(name: String): String {
        val file = File(root, name)
        assertTrue("render: install code: the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** [text] with comments removed and string literals blanked, and every run of spaces made one. */
    private fun codeOf(text: String): String =
        STRING_OR_COMMENT.replace(text) { m -> if (m.value.startsWith("/")) "" else "\"\"" }
            .replace(SPACES, " ")

    /** [text] with comments removed and string literals kept, and every run of spaces made one. */
    private fun codeWithStringsOf(text: String): String = COMMENT.replace(text, "").replace(SPACES, " ")

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue(
            "render: install code: $what must be reported with '$part', got $problems",
            problems.any { it.contains(part) },
        )

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: install code: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        val hits = Regex(Regex.escape(old)).findAll(text).count()
        assertTrue("render: install code: not exactly once: $old", hits == 1)
        return text.replace(old, new)
    }

    /** The named problems with the install code box in [text]; an empty list means every rule holds. */
    private fun boxProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        // `toString()` is the required read; a `String(` constructor is not. Drop the read first.
        val kept = codeWithStringsOf(text).replace("toString(", "")
        return listOfNotNull(
            "the install code box is not the shared one-line box"
                .takeUnless { code.contains(": AuthFieldView(") },
            "the install code box allows more than one line"
                .takeIf { Regex("maxLines\\s*=\\s*[2-9]").containsMatchIn(code) },
            "the install code box is masked"
                .takeIf { code.contains("InputType.TYPE_TEXT_VARIATION_PASSWORD") },
            "the install code box does not turn suggestions off"
                .takeUnless { code.contains("InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS") },
            "the install code box has not the Done key"
                .takeUnless { code.contains("imeOptions = EditorInfo.IME_ACTION_DONE") },
            "the install code box has not the shared length limit"
                .takeUnless { code.contains("InputFilter.LengthFilter(AuthPolicy.INSTALL_CODE_MAX_CHARS)") },
            "the install code box hardcodes the cap"
                .takeIf { Regex("LengthFilter\\(\\s*\\d+\\s*\\)").containsMatchIn(code) },
            "the install code box does not read the typed text"
                .takeUnless { code.contains("fun typedCode(): String = text.toString()") },
        ) + FORBIDDEN_IN_INSTALL_CODE.filter { kept.contains(it) }
            .map { "the install code file holds a forbidden token: $it" }
    }

    @Test
    fun `the real box meets every rule`() {
        assertQuiet("the real box", boxProblemsIn(box))
    }

    @Test
    fun `a box that is not the shared one-line box is reported`() {
        val plain = replaceOnce(box, ": AuthFieldView(", ": android.widget.EditText(")
        assertFires("a plain EditText", boxProblemsIn(plain), "shared one-line box")
    }

    @Test
    fun `a box that allows more than one line is reported`() {
        val old = "        imeOptions = EditorInfo.IME_ACTION_DONE\n"
        val two = replaceOnce(box, old, "$old        maxLines = 2\n")
        assertFires("two lines", boxProblemsIn(two), "more than one line")
    }

    @Test
    fun `a masked box is reported`() {
        val old = "    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,"
        val masked = replaceOnce(
            box,
            old,
            "    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD" +
                " or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,",
        )
        assertFires("a masked box", boxProblemsIn(masked), "masked")
    }

    @Test
    fun `a box that allows suggestions is reported`() {
        val old = "    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,"
        val open = replaceOnce(box, old, "    InputType.TYPE_CLASS_TEXT,")
        assertFires("no suggestion flag", boxProblemsIn(open), "turn suggestions off")
    }

    @Test
    fun `a box with the wrong key is reported`() {
        val old = "        imeOptions = EditorInfo.IME_ACTION_DONE"
        val next = replaceOnce(box, old, "        imeOptions = EditorInfo.IME_ACTION_NEXT")
        assertFires("the wrong key", boxProblemsIn(next), "Done key")
    }

    @Test
    fun `a box with a hardcoded cap is reported`() {
        val old = "InputFilter.LengthFilter(AuthPolicy.INSTALL_CODE_MAX_CHARS)"
        val hard = replaceOnce(box, old, "InputFilter.LengthFilter(128)")
        assertFires("a hardcoded cap", boxProblemsIn(hard), "shared length limit")
        assertFires("a hardcoded cap", boxProblemsIn(hard), "hardcodes the cap")
    }

    @Test
    fun `a box that reads something else is reported`() {
        val old = "    fun typedCode(): String = text.toString()"
        val empty = replaceOnce(box, old, "    fun typedCode(): String = \"\"")
        assertFires("an empty read", boxProblemsIn(empty), "does not read the typed text")
    }

    @Test
    fun `a box that logs the typed code is reported`() {
        val old = "        filters = arrayOf(InputFilter.LengthFilter(AuthPolicy.INSTALL_CODE_MAX_CHARS))\n"
        val logged = replaceOnce(box, old, "$old        Log.d(\"auth\", text.toString())\n")
        assertFires("a log line", boxProblemsIn(logged), "a forbidden token")
    }

    @Test
    fun `a box that prints the typed code is reported`() {
        val old = "        filters = arrayOf(InputFilter.LengthFilter(AuthPolicy.INSTALL_CODE_MAX_CHARS))\n"
        val printed = replaceOnce(box, old, "$old        println(text.toString())\n")
        assertFires("a println", boxProblemsIn(printed), "a forbidden token")
    }

    @Test
    fun `a box with a string template is reported`() {
        val old = "        filters = arrayOf(InputFilter.LengthFilter(AuthPolicy.INSTALL_CODE_MAX_CHARS))\n"
        val templated = replaceOnce(box, old, "$old        val probe = \"code \$text\"\n")
        assertFires("a template", boxProblemsIn(templated), "a forbidden token")
    }

    @Test
    fun `a box that builds a String is reported`() {
        val old = "        filters = arrayOf(InputFilter.LengthFilter(AuthPolicy.INSTALL_CODE_MAX_CHARS))\n"
        val built = replaceOnce(box, old, "$old        val probe = String(text.toString().toByteArray())\n")
        assertFires("a built String", boxProblemsIn(built), "a forbidden token")
    }

    private companion object {
        /** A comment only; string literals stay. */
        val COMMENT = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")

        /** A comment, or a string literal as it is written. */
        val STRING_OR_COMMENT = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"")
        val SPACES = Regex("""\s+""")

        /** What the install code file must never hold in code: a log, a print, a template, a built String. */
        val FORBIDDEN_IN_INSTALL_CODE = listOf("Log.", "println", "$", "String(")
    }
}
