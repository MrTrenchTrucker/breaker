package dev.breaker.dictation.ui.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The value box as a real text input, pinned as text, the way the other render source rules are.
 * Each rule runs first on an edited copy of the real file, so a rule that cannot fire is reported,
 * and then on the real file. An edit fails by name when its text is not there exactly once.
 */
class TextEntryViewRulesTest {

    private val root = File("src/main/kotlin/dev/breaker/dictation/ui")
    private val entry = sourceOf("render/TextEntryView.kt")
    private val rule = sourceOf("render/EntryCommitRule.kt")
    private val renderer = sourceOf("render/ScreenRenderer.kt")
    private val valueField = sourceOf("render/ValueFieldView.kt")
    private val doneBranch =
        "actionId == EditorInfo.IME_ACTION_DONE) { rule.finished(text.toString()) true }"
    private val deferred = "post { if (isAttachedToWindow) rule.finished(text.toString()) }"
    private val outline = "ControlPart.FIELD_OUTLINE, theme"
    private val fallback = "val key = node.entryKey ?: return valueFieldView(context, node, theme)"
    private val keyed = "return TextEntryView(context, theme, node, key, onIntent)"
    private val mutedStart = "private val startText: String = if (node.muted) \"\" else node.text"
    private val mutedHint = "hint = if (node.muted) node.text else null"
    private val rendererCall =
        "is ValueField -> fieldView(context, node, theme, onIntent).apply { layoutParams = rowParams() }"

    private fun sourceOf(relative: String): String {
        val file = File(root, relative)
        assertTrue("render: text entry: the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun allSources(): List<Pair<String, String>> {
        assertTrue("render: text entry: the source root was not found", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it.readText() }
            .toList()
    }

    /** [text] with comments removed; string literals are blanked, or kept when [keep] is set. */
    private fun shaped(text: String, keep: Boolean): String =
        STRING_OR_COMMENT.replace(text) { m ->
            if (m.value.startsWith("/")) "" else if (keep) m.value else "\"\""
        }.replace(SPACES, " ")

    private fun codeOf(text: String): String = shaped(text, keep = false)

    private fun literalOf(text: String): String = shaped(text, keep = true)

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue(
            "render: text entry: $what must be reported with '$part', got $problems",
            problems.any { it.contains(part) },
        )

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: text entry: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        val hits = Regex(Regex.escape(old)).findAll(text).count()
        assertTrue("render: text entry: not exactly once: $old", hits == 1)
        return text.replace(old, new)
    }

    /** The named problems with the value box in [text]; an empty list means every rule holds. */
    private fun entryProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        val literal = literalOf(text)
        return listOfNotNull(
            "the view is not an EditText".takeUnless { code.contains(": EditText(context)") },
            "the view does not act on the Done action or commit there"
                .takeUnless { code.contains(doneBranch) },
            "the view has no focus loss check".takeUnless { code.contains("if (!gainFocus)") },
            "the focus loss is not deferred with post and isAttachedToWindow"
                .takeUnless { code.contains(deferred) },
            "the view does not build the commit rule on its start text"
                .takeUnless { code.contains("EntryCommitRule(startText)") },
            "the view does not send ScreenIntent.SetSetting with its key and the typed text"
                .takeUnless { code.contains("ScreenIntent.SetSetting(entryKey, typed)") },
            "the box is not a URI text box".takeUnless { code.contains("TYPE_TEXT_VARIATION_URI") },
            "the box does not use the Done key"
                .takeUnless { code.contains("imeOptions = EditorInfo.IME_ACTION_DONE") },
            "the box keeps a stock background".takeUnless { code.contains("background = null") },
            "the view does not paint the kit fill and outline"
                .takeUnless { code.contains("ControlPart.FIELD, theme") && code.contains(outline) },
            "the view does not fall back to the display field when there is no key"
                .takeUnless { code.contains(fallback) },
            "a keyed node does not get the input view".takeUnless { code.contains(keyed) },
            "the muted start text is not empty".takeUnless { literal.contains(mutedStart) },
            "the muted hint is not the stored text".takeUnless { literal.contains(mutedHint) },
            "a colour literal is written".takeIf { Regex(COLOUR_SHAPES).containsMatchIn(literal) },
        )
    }

    /** The named problems with the renderer's value branch in [text]. */
    private fun rendererProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the renderer does not call fieldView in the value branch"
                .takeUnless { code.contains(rendererCall) },
            "the renderer still calls valueFieldView directly".takeIf { code.contains("valueFieldView(") },
        )
    }

    /** The named problems with the display field in [text]: it stays out of focus and out of touch. */
    private fun valueFieldProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the display field is not read-only: focusable is not false"
                .takeUnless { code.contains("isFocusable = false") },
            "the display field is not read-only: clickable is not false"
                .takeUnless { code.contains("isClickable = false") },
        )
    }

    /** The named problems with the commit rule file in [text]: plain Kotlin, marked sent first. */
    private fun ruleProblemsIn(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the rule file names the framework".takeIf { Regex("""\bandroid\.""").containsMatchIn(code) },
            "the rule does not mark sent before it sends"
                .takeUnless { code.indexOf("sent = true") in 0 until code.indexOf("send(typed)") },
        )
    }

    /** The source files outside render/ that name EditText in their code. */
    private fun editTextOutsideRender(sources: List<Pair<String, String>>): List<String> =
        sources
            .filter { (path, _) -> !path.startsWith("render/") }
            .filter { (_, text) -> Regex("""\bEditText\b""").containsMatchIn(codeOf(text)) }
            .map { (path, _) -> path }

    @Test
    fun `the real value box meets every entry rule`() {
        assertQuiet("the real value box", entryProblemsIn(entry))
    }

    @Test
    fun `a view that is not an edit box is reported`() {
        val edited = replaceOnce(entry, ": EditText(context)", ": View(context)")
        assertFires("a view that is not an EditText", entryProblemsIn(edited), "not an EditText")
    }

    @Test
    fun `a Done action that does not commit at once is reported`() {
        val noCommit = replaceOnce(entry, "rule.finished(text.toString())\n                true", "true")
        assertFires("a Done branch with no commit", entryProblemsIn(noCommit), "does not act on the Done")
        val other = replaceOnce(entry, "IME_ACTION_DONE) {", "IME_ACTION_NEXT) {")
        assertFires("a Done check on another action", entryProblemsIn(other), "does not act on the Done")
    }

    @Test
    fun `a focus loss that is not checked is reported`() {
        val gone = replaceOnce(entry, "if (!gainFocus) post", "if (gainFocus) post")
        assertFires("no loss check", entryProblemsIn(gone), "no focus loss check")
    }

    @Test
    fun `a focus loss that commits without the post or the attached check is reported`() {
        val immediate = replaceOnce(entry, DEFERRED_LINE, "if (!gainFocus) rule.finished(text.toString())")
        assertFires("an immediate commit", entryProblemsIn(immediate), "not deferred with post")
        val noAttach = replaceOnce(entry, "if (isAttachedToWindow) rule.finished", "rule.finished")
        assertFires("a post with no attached check", entryProblemsIn(noAttach), "not deferred with post")
    }

    @Test
    fun `a box that builds the commit rule on another text is reported`() {
        val other = replaceOnce(entry, "EntryCommitRule(startText)", "EntryCommitRule(node.text)")
        assertFires("the rule on the node text", entryProblemsIn(other), "does not build the commit rule")
    }

    @Test
    fun `a box that sends another intent is reported`() {
        val other = replaceOnce(entry, "ScreenIntent.SetSetting(entryKey, typed)", "ScreenIntent.Back")
        assertFires("another intent", entryProblemsIn(other), "does not send ScreenIntent.SetSetting")
    }

    @Test
    fun `a box that is not a URI box or lacks the Done key is reported`() {
        val plain = replaceOnce(entry, "TYPE_TEXT_VARIATION_URI", "TYPE_TEXT_VARIATION_NORMAL")
        assertFires("a plain text box", entryProblemsIn(plain), "not a URI text box")
        val noDone = replaceOnce(entry, "imeOptions = EditorInfo.IME_ACTION_DONE", "imeOptions = 0")
        assertFires("no Done key", entryProblemsIn(noDone), "does not use the Done key")
    }

    @Test
    fun `a box with a stock background is reported`() {
        val stock = replaceOnce(entry, "        background = null\n", "")
        assertFires("the stock background", entryProblemsIn(stock), "keeps a stock background")
    }

    @Test
    fun `a box that paints no kit fill and outline is reported`() {
        val noOutline = replaceOnce(entry, outline, "ControlPart.LABEL, theme")
        assertFires("no outline", entryProblemsIn(noOutline), "does not paint the kit fill and outline")
    }

    @Test
    fun `a node with no key that does not fall back to the display field is reported`() {
        val noFallback = replaceOnce(entry, fallback, "val key = node.entryKey ?: return View(context)")
        assertFires("no fallback", entryProblemsIn(noFallback), "does not fall back to the display field")
    }

    @Test
    fun `a keyed node that gets the display field is reported`() {
        val displayOnly = replaceOnce(entry, keyed, "return valueFieldView(context, node, theme)")
        assertFires("the keyed node on the display field", entryProblemsIn(displayOnly), "keyed node")
    }

    @Test
    fun `a muted box that starts with text or hides the stored text is reported`() {
        val notEmpty = replaceOnce(entry, mutedStart, "private val startText: String = node.text")
        assertFires("a muted box with text", entryProblemsIn(notEmpty), "muted start text is not empty")
        val noHint = replaceOnce(entry, mutedHint, "hint = null")
        assertFires("no hint", entryProblemsIn(noHint), "muted hint is not the stored text")
    }

    @Test
    fun `a colour written into the box is reported`() {
        // The hex run is split so that no plain literal in this file carries seven or more digits.
        val swatch = "private val swatch = 0x" + "FF" + "1E7A46" + ".toInt()\n    "
        val edited = replaceOnce(entry, "private val density", swatch + "private val density")
        assertFires("a hex colour number", entryProblemsIn(edited), "a colour literal is written")
    }

    @Test
    fun `the renderer draws the value branch through the input view`() {
        assertQuiet("the real renderer", rendererProblemsIn(renderer))
        val call = "fieldView(context, node, theme, onIntent)"
        val displayOnly = replaceOnce(renderer, call, "valueFieldView(context, node, theme)")
        assertFires("the display field called directly", rendererProblemsIn(displayOnly), "still calls")
    }

    @Test
    fun `the display field stays out of focus and out of touch`() {
        assertQuiet("the real display field", valueFieldProblemsIn(valueField))
        val focusable = replaceOnce(valueField, "isFocusable = false", "isFocusable = true")
        assertFires("a focusable display field", valueFieldProblemsIn(focusable), "not read-only: focusable")
        val clickable = replaceOnce(valueField, "isClickable = false", "isClickable = true")
        assertFires("a clickable display field", valueFieldProblemsIn(clickable), "not read-only: clickable")
    }

    @Test
    fun `the commit rule file is plain Kotlin and is marked sent before it sends`() {
        assertQuiet("the real rule file", ruleProblemsIn(rule))
        val header = "internal class EntryCommitRule"
        val framework = replaceOnce(rule, header, "import android.view.View\n\n" + header)
        assertFires("a framework import", ruleProblemsIn(framework), "names the framework")
        val swapped = "send(typed)\n        sent = true"
        val order = replaceOnce(rule, "sent = true\n        send(typed)", swapped)
        assertFires("sent after the send", ruleProblemsIn(order), "does not mark sent before it sends")
    }

    @Test
    fun `no source file outside render names EditText`() {
        assertEquals("EditText outside render: ", emptyList<String>(), editTextOutsideRender(allSources()))
        val leaked = listOf("screen/Leak.kt" to "val leaked = EditText(context)")
        assertEquals(listOf("screen/Leak.kt"), editTextOutsideRender(leaked))
    }

    private companion object {
        /** A comment, or a string literal as it is written. */
        val STRING_OR_COMMENT = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"")
        val SPACES = Regex("""\s+""")

        /** The colour shapes a literal can take: a hex string, a hex number or the colour class. */
        const val COLOUR_SHAPES = "0[xX][0-9a-fA-F]{6}|#[0-9a-fA-F]{6}|android\\.graphics\\.Color"
        const val DEFERRED_LINE =
            "if (!gainFocus) post { if (isAttachedToWindow) rule.finished(text.toString()) }"
    }
}
