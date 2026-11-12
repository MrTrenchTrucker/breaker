package dev.breaker.dictation.ui.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The auth text boxes, pinned as text, the way the other render source rules are. Each rule is a
 * function over a source text that returns named problems. It runs first on an edited copy of the
 * real file, so a rule that cannot fire is reported, and then on the real file, where it must be
 * quiet. An edit fails by name when its text is not there exactly once.
 */
class AuthInputViewRulesTest {

    private val root = File("src/main/kotlin/dev/breaker/dictation/ui/render")
    private val field = sourceOf("AuthFieldView.kt")
    private val password = sourceOf("PasswordEntryView.kt")
    private val username = sourceOf("UsernameEntryView.kt")

    private fun sourceOf(name: String): String {
        val file = File(root, name)
        assertTrue("render: auth input: the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** [text] with comments removed and string literals blanked, and every run of spaces made one. */
    private fun codeOf(text: String): String =
        STRING_OR_COMMENT.replace(text) { m -> if (m.value.startsWith("/")) "" else "\"\"" }
            .replace(SPACES, " ")

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue(
            "render: auth input: $what must be reported with '$part', got $problems",
            problems.any { it.contains(part) },
        )

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: auth input: $what must not be reported", emptyList<String>(), problems)

    /** [text] with comments removed and string literals kept, and every run of spaces made one. */
    private fun codeWithStringsOf(text: String): String = COMMENT.replace(text, "").replace(SPACES, " ")

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        val hits = Regex(Regex.escape(old)).findAll(text).count()
        assertTrue("render: auth input: not exactly once: $old", hits == 1)
        return text.replace(old, new)
    }

    /** The named problems with the shared box in [text]. */
    private fun fieldProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the box does not ask autofill to skip it and its children"
                .takeUnless { code.contains("IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS") },
            "the box saves its text into the saved state"
                .takeUnless { code.contains("isSaveEnabled = false") },
            "the box allows more than one line".takeUnless { code.contains("maxLines = 1 ") },
            "the box does not tell a screen reader its label"
                .takeUnless { code.contains("info.hintText = label") },
        )
    }

    /** The named problems with the password box in [text]. */
    private fun passwordProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        val wipe = code.indexOf("fun wipeTyped() {")
        val clear = code.indexOf(".clear()", wipe)
        val detach = code.indexOf("override fun onDetachedFromWindow() {")
        val detachBody = if (detach < 0) "" else code.substring(detach).substringBefore("}")
        val overwrites = wipe >= 0 && clear > wipe && code.substring(wipe, clear).contains(".replace(")
        val kept = codeWithStringsOf(text)
        val zeroLiteral = kept.contains("private const val ZERO_TEXT = \"\\u0000\"")
        val writesZero = kept.contains("typed.replace(index, index + 1, ZERO_TEXT)")
        val menuLines = REFUSING_MEMBERS.filterNot {
            Regex("fun $it\\([^)]*\\): Boolean = false").containsMatchIn(code)
        }
        val wipeAt = detachBody.indexOf("wipeTyped()")
        val wipesFirst = wipeAt >= 0 && wipeAt < detachBody.indexOf("super.onDetachedFromWindow()")
        return listOfNotNull(
            "the box is not a password box"
                .takeUnless { code.contains("InputType.TYPE_TEXT_VARIATION_PASSWORD") },
            "the box can be long pressed".takeUnless { code.contains("isLongClickable = false") },
            "the box does not refuse the selection menu"
                .takeUnless { code.contains("customSelectionActionModeCallback = RefusingMenu") },
            "the box does not refuse the insertion menu"
                .takeUnless { code.contains("customInsertionActionModeCallback = RefusingMenu") },
            "the box does not turn suggestions off"
                .takeUnless { code.contains("InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS") },
            "the box does not turn personalized learning off"
                .takeUnless { code.contains(IME_DONE_NO_LEARNING) },
            "the take does not copy every typed character"
                .takeUnless { code.contains("text.getChars(0, text.length, out, 0)") },
            "the wipe does not name the NUL character as its overwrite text".takeUnless { zeroLiteral },
            "the wipe does not overwrite each character with the NUL text".takeUnless { writesZero },
            "the detach does not wipe the typed text before the superclass".takeUnless { wipesFirst },
            "the wipe does not overwrite the characters before it clears".takeUnless { overwrites },
        ) + menuLines.map { "the refusing menu does not answer false from $it" } +
            FORBIDDEN_IN_PASSWORD.filter { code.contains(it) }.map { "the password file holds $it" }
    }

    /** The named problems with the user name box in [text]. */
    private fun usernameProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the user name box has not the shared length limit"
                .takeUnless { code.contains("InputFilter.LengthFilter(AuthPolicy.USERNAME_MAX_CHARS)") },
            "the user name box does not turn suggestions off"
                .takeUnless { code.contains("InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS") },
            "the user name box has not the Next key"
                .takeUnless { code.contains("imeOptions = EditorInfo.IME_ACTION_NEXT") },
        )
    }

    @Test
    fun `the real boxes meet every input rule`() {
        assertQuiet("the real shared box", fieldProblemsIn(field))
        assertQuiet("the real password box", passwordProblemsIn(password))
        assertQuiet("the real user name box", usernameProblemsIn(username))
    }

    @Test
    fun `a password box that is not masked is reported`() {
        val old = "InputType.TYPE_TEXT_VARIATION_PASSWORD"
        val plain = replaceOnce(password, old, "InputType.TYPE_TEXT_VARIATION_NORMAL")
        assertFires("a plain text box", passwordProblemsIn(plain), "not a password box")
    }

    @Test
    fun `a box that does not ask autofill to skip it is reported`() {
        val old = "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS"
        val auto = replaceOnce(field, old, "IMPORTANT_FOR_AUTOFILL_AUTO")
        assertFires("an autofill box", fieldProblemsIn(auto), "autofill")
    }

    @Test
    fun `a box that saves its text into the saved state is reported`() {
        val saved = replaceOnce(field, "isSaveEnabled = false", "isSaveEnabled = true")
        assertFires("a saving box", fieldProblemsIn(saved), "saves its text")
    }

    @Test
    fun `a password box that builds a string from the typed text is reported`() {
        val old = "text.getChars(0, text.length, out, 0)"
        val copied = replaceOnce(password, old, "text.toString().toCharArray().copyInto(out)")
        assertFires("a string from the text", passwordProblemsIn(copied), "holds toString")
        val logged = replaceOnce(password, old, "Log.d(TAG, text)\n        $old")
        assertFires("a log line", passwordProblemsIn(logged), "holds Log.")
    }

    @Test
    fun `a password box that can be long pressed is reported`() {
        val old = "isLongClickable = false"
        val pressed = replaceOnce(password, old, "isLongClickable = true")
        assertFires("a long pressable box", passwordProblemsIn(pressed), "can be long pressed")
    }

    @Test
    fun `a password box that does not wipe on detach is reported`() {
        val old = "        wipeTyped()\n        super.onDetachedFromWindow()"
        val kept = replaceOnce(password, old, "        super.onDetachedFromWindow()")
        assertFires("a detach with no wipe", passwordProblemsIn(kept), "detach does not wipe")
        val late = replaceOnce(password, old, "        super.onDetachedFromWindow()\n        wipeTyped()")
        assertFires("a wipe after the superclass", passwordProblemsIn(late), "detach does not wipe")
    }

    @Test
    fun `a wipe that only clears without overwriting is reported`() {
        val old = "            typed.replace(index, index + 1, ZERO_TEXT)\n"
        val cleared = replaceOnce(password, old, "")
        assertFires("a clear with no overwrite", passwordProblemsIn(cleared), "does not overwrite")
    }

    @Test
    fun `a user name box with a limit that is not the shared one is reported`() {
        val old = "InputFilter.LengthFilter(AuthPolicy.USERNAME_MAX_CHARS)"
        val one = "InputFilter.LengthFilter(AuthPolicy.USERNAME_MAX_CHARS + 1)"
        val loose = replaceOnce(username, old, one)
        assertFires("a limit of one more", usernameProblemsIn(loose), "shared length limit")
    }

    @Test
    fun `a password box that allows suggestions is reported`() {
        val old = "        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,\n"
        val open = replaceOnce(password, old, "        0,\n")
        assertFires("no suggestion flag", passwordProblemsIn(open), "turn suggestions off")
    }

    @Test
    fun `a user name box that allows suggestions is reported`() {
        val old = "InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,"
        val open = replaceOnce(username, old, "InputType.TYPE_CLASS_TEXT,")
        assertFires("no suggestion flag", usernameProblemsIn(open), "turn suggestions off")
    }

    @Test
    fun `a password box that allows personalized learning is reported`() {
        val old = "EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING"
        val learning = replaceOnce(password, old, "EditorInfo.IME_ACTION_DONE")
        assertFires("no learning flag", passwordProblemsIn(learning), "personalized learning")
    }

    @Test
    fun `a user name box without the Next key is reported`() {
        val old = "imeOptions = EditorInfo.IME_ACTION_NEXT"
        val done = replaceOnce(username, old, "imeOptions = EditorInfo.IME_ACTION_DONE")
        assertFires("the wrong key", usernameProblemsIn(done), "Next key")
    }

    @Test
    fun `a box that allows more than one line is reported`() {
        val two = replaceOnce(field, "maxLines = 1", "maxLines = 2")
        assertFires("two lines", fieldProblemsIn(two), "more than one line")
        val ten = replaceOnce(field, "maxLines = 1", "maxLines = 10")
        assertFires("ten lines", fieldProblemsIn(ten), "more than one line")
    }

    @Test
    fun `a box that gives a screen reader no hint is reported`() {
        val bare = replaceOnce(field, "info.hintText = label", "info.hintText = null")
        assertFires("a null hint", fieldProblemsIn(bare), "screen reader")
    }

    @Test
    fun `a refusing menu that answers true from a member is reported`() {
        for (member in REFUSING_MEMBERS) {
            val line = Regex("override fun $member\\([^)]*\\): Boolean = false")
            val old = line.find(password)?.value.orEmpty()
            val open = replaceOnce(password, old, old.replace("= false", "= true"))
            assertFires("an open $member", passwordProblemsIn(open), "false from $member")
        }
    }

    @Test
    fun `a password box that does not set both menu callbacks to the refusing one is reported`() {
        val selection = replaceOnce(
            password,
            "customSelectionActionModeCallback = RefusingMenu",
            "customSelectionActionModeCallback = null",
        )
        assertFires("a free selection menu", passwordProblemsIn(selection), "selection menu")
        val insertion = replaceOnce(
            password,
            "customInsertionActionModeCallback = RefusingMenu",
            "customInsertionActionModeCallback = null",
        )
        assertFires("a free insertion menu", passwordProblemsIn(insertion), "insertion menu")
    }

    @Test
    fun `a take that copies fewer characters than were typed is reported`() {
        val old = "text.getChars(0, text.length, out, 0)"
        val short = replaceOnce(password, old, "text.getChars(0, 0, out, 0)")
        assertFires("a short copy", passwordProblemsIn(short), "copy every typed character")
    }

    @Test
    fun `a wipe that overwrites with another letter than NUL is reported`() {
        val old = "\"\\u0000\""
        val letter = replaceOnce(password, old, "\"x\"")
        assertFires("a letter overwrite", passwordProblemsIn(letter), "NUL character")
    }

    private companion object {
        /** A comment only; string literals stay. */
        val COMMENT = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")

        /** The three Boolean members of the refusing menu. */
        val REFUSING_MEMBERS = listOf("onCreateActionMode", "onPrepareActionMode", "onActionItemClicked")

        /** The IME options of the password box, as the file writes them. */
        const val IME_DONE_NO_LEARNING =
            "EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING"

        /** A comment, or a string literal as it is written. */
        val STRING_OR_COMMENT = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"")
        val SPACES = Regex("""\s+""")

        /** What the password file must never hold in code: a string from the text, a log, a template. */
        val FORBIDDEN_IN_PASSWORD = listOf("toString", "String(", "Log.", "println", "$")
    }
}
