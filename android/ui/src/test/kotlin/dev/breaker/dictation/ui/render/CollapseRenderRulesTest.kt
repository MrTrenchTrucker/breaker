package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the collapsing text and of the kit mapping, checked on source text the way the
 * switch rules check the kit. Each rule runs first on short made-up samples, so a rule that
 * cannot fire is seen here, and only then on the real file read from the main tree. A file that
 * cannot be found is a violation, never a pass. Comments and string literals are blanked first.
 */
class CollapseRenderRulesTest {
    private val VIEW = "render/CollapsibleTextView.kt"
    private val RENDERER = "render/ScreenRenderer.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("render: collapse: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: collapse: $what must not be reported", emptyList<String>(), problems)

    /** The text of a main file, read from the main tree. */
    private fun realFile(path: String): String {
        val text = MAIN_SOURCES.toMap()[path]
        assertTrue("render: collapse: $path was not read from the main tree", text != null)
        return text ?: ""
    }

    private fun codeOf(text: String): String = withoutCommentsAndStrings(text)

    // ---- The text view asks the one collapse decision, once ----

    private val COLLAPSE_CALL = Regex("""\bcollapse\s*\(""")

    private fun collapseProblems(text: String): List<String> {
        val calls = COLLAPSE_CALL.findAll(codeOf(text)).count()
        return if (calls == 1) emptyList() else listOf("collapse( is called $calls time(s), not once")
    }

    /** A failure here means the view decides its own lines, so the view and the collapse rule can disagree. */
    @Test
    fun `the text view asks the one collapse decision exactly once`() {
        val call = "        val result = collapse(count, opened)\n"
        assertFires("the call twice", collapseProblems(call + call), "not once")
        assertFires("the call removed", collapseProblems("        val result = count\n"), "not once")
        assertQuiet("the call once", collapseProblems(call))
        assertQuiet("the real view", collapseProblems(realFile(VIEW)))
    }

    // ---- The line count comes from a static layout built like the text view ----

    private val BUILDER = Regex("""(?:StaticLayout\s*\.\s*)?Builder\s*\.\s*obtain\s*\(""")
    private val BREAK_SETTERS = listOf("setBreakStrategy", "setHyphenationFrequency", "setIncludePad", "setTextDirection")

    private fun builderProblems(text: String): List<String> {
        val code = codeOf(text)
        val problems = mutableListOf<String>()
        if (!BUILDER.containsMatchIn(code)) problems.add("no StaticLayout builder is obtained")
        for (setter in BREAK_SETTERS) {
            if (!Regex("""\.\s*$setter\s*\(""").containsMatchIn(code)) problems.add("missing: $setter")
        }
        return problems
    }

    /** A failure here means the line count is taken with other break settings than the text view draws with. */
    @Test
    fun `the text view counts lines with a static layout builder that copies the break settings`() {
        val layout = "        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)\n" +
            "            .setBreakStrategy(breakStrategy)\n" +
            "            .setHyphenationFrequency(hyphenation)\n" +
            "            .setIncludePad(includePad)\n" +
            "            .setTextDirection(direction)\n" +
            "            .build()\n"
        assertFires(
            "the include pad setting removed",
            builderProblems(layout.replace("            .setIncludePad(includePad)\n", "")),
            "setIncludePad",
        )
        assertFires(
            "the builder replaced by a constructor",
            builderProblems(layout.replace("StaticLayout.Builder.obtain(", "StaticLayout(")),
            "no StaticLayout builder",
        )
        assertQuiet("the builder with its four settings", builderProblems(layout))
        assertQuiet("the real view", builderProblems(realFile(VIEW)))
    }

    // ---- A collapsed text ends with the end ellipsis ----

    private val TRUNCATE_END = Regex("""\bTruncateAt\s*\.\s*END\b""")
    private val ELLIPSIZE_SET = Regex("""(?:\bellipsize\s*=(?!=)|\bsetEllipsize\s*\()""")
    private val MAX_LINES_SET = Regex("""(?:\bmaxLines\s*=(?!=)|\bsetMaxLines\s*\()""")

    private fun ellipsisProblems(text: String): List<String> {
        val code = codeOf(text)
        return listOfNotNull(
            "the collapsed text does not end with TruncateAt END".takeUnless { TRUNCATE_END.containsMatchIn(code) },
            "the ellipsize is not assigned".takeUnless { ELLIPSIZE_SET.containsMatchIn(code) },
            "the maxLines is not assigned".takeUnless { MAX_LINES_SET.containsMatchIn(code) },
        )
    }

    /** A failure here means a collapsed text is cut without the end mark, or the limit and the mark are not both set. */
    @Test
    fun `a collapsed text ends with the end ellipsis and both its limit and mark are set`() {
        val wanted = "        val wanted = if (result.maxLines == null) null else TextUtils.TruncateAt.END\n"
        val ellipsisLine = "        if (ellipsize != wanted) ellipsize = wanted\n"
        val limitLine = "        if (maxLines != limit) maxLines = limit\n"
        val whole = wanted + ellipsisLine + limitLine
        assertFires("the start ellipsis", ellipsisProblems(whole.replace("TruncateAt.END", "TruncateAt.START")), "TruncateAt END")
        assertFires("the wanted line removed", ellipsisProblems(whole.replace(wanted, "")), "TruncateAt END")
        assertFires("the ellipsize assignment removed", ellipsisProblems(whole.replace(ellipsisLine, "")), "ellipsize")
        assertFires("the maxLines assignment removed", ellipsisProblems(whole.replace(limitLine, "")), "maxLines")
        assertQuiet("the end mark and both assignments", ellipsisProblems(whole))
        assertQuiet("the real view", ellipsisProblems(realFile(VIEW)))
    }

    // ---- The sibling toggle is shown from the middle value ----

    private val MIDDLE = """(?:\w+\.)?middle\b"""
    private val NONE = """Middle\.NONE\b"""
    private val GONE_WHEN_NONE = Regex(
        """(?:$MIDDLE\s*==\s*$NONE|$NONE\s*==\s*$MIDDLE)\s*\)\s*(?:View\.)?GONE\s+else\s+(?:View\.)?VISIBLE""",
    )
    private val VISIBLE_WHEN_NONE = Regex(
        """(?:$MIDDLE\s*!=\s*$NONE|$NONE\s*!=\s*$MIDDLE)\s*\)\s*(?:View\.)?VISIBLE\s+else\s+(?:View\.)?GONE""",
    )

    private fun toggleProblems(text: String): List<String> {
        val code = codeOf(text)
        return if (GONE_WHEN_NONE.containsMatchIn(code) || VISIBLE_WHEN_NONE.containsMatchIn(code)) emptyList()
        else listOf("the sibling toggle is not set VISIBLE or GONE from the middle value")
    }

    /** A failure here means the toggle is shown in the wrong state, so Show all or Hide is offered when nothing was cut. */
    @Test
    fun `the sibling toggle is shown only when the middle value is not none`() {
        val toggle = "        toggle.visibility = if (result.middle == Middle.NONE) View.GONE else View.VISIBLE\n"
        assertFires(
            "the none branch shown",
            toggleProblems(toggle.replace("View.GONE else", "View.VISIBLE else")),
            "middle value",
        )
        assertFires(
            "the branches swapped",
            toggleProblems(toggle.replace("View.GONE else View.VISIBLE", "View.VISIBLE else View.GONE")),
            "middle value",
        )
        assertFires("the line removed", toggleProblems("        toggle.visibility = View.VISIBLE\n"), "middle value")
        assertQuiet("the none test as written", toggleProblems(toggle))
        assertQuiet(
            "the none test with its operands reversed",
            toggleProblems("        toggle.visibility = if (Middle.NONE != result.middle) View.VISIBLE else View.GONE\n"),
        )
        assertQuiet("the real view or renderer", toggleProblems(realFile(VIEW) + "\n" + realFile(RENDERER)))
    }

    // ---- Each kit look maps to its kit view, and a null kit keeps the plain button ----

    private val KIT_VIEWS = listOf(
        "RAISED" to "Button3dView",
        "NEUTRAL" to "NeutralButtonView",
        "DANGER" to "DeleteButtonView",
        "OUTLINED" to "OutlinedButtonView",
    )

    private fun mapsTo(code: String, look: String, view: String): Boolean =
        Regex("""(?:KitLook\.)?$look\s*->[\s{]*(?:[\w.]+\.)?$view\b""").containsMatchIn(code)

    private fun kitProblems(text: String): List<String> {
        val code = codeOf(text)
        val views = KIT_VIEWS
            .filterNot { (look, view) -> mapsTo(code, look, view) }
            .map { (look, view) -> "missing: $look to $view" }
        val plain = listOfNotNull(
            "missing: the kit == null branch"
                .takeUnless { Regex("""\bkit\s*==\s*null\b|\bnull\s*==\s*kit\b|\bnull\s*->""").containsMatchIn(code) },
            "missing: the plain Button"
                .takeUnless { Regex("""\bButton\s*\(""").containsMatchIn(code) },
        )
        return views + plain
    }

    /** A failure here means a kit look draws the wrong kit view, or a setup button with no kit loses its plain button. */
    @Test
    fun `each kit look maps to its kit view and the plain button stays for a null kit`() {
        val sample = """
            when (kit) {
                KitLook.RAISED -> Button3dView(context, text, theme)
                KitLook.NEUTRAL -> NeutralButtonView(context, text, theme)
                KitLook.DANGER -> DeleteButtonView(context, text, theme)
                KitLook.OUTLINED -> OutlinedButtonView(context, text, theme)
                null -> Button(context)
            }
        """
        assertFires(
            "the raised look drawn as the danger view",
            kitProblems(sample.replace("KitLook.RAISED -> Button3dView", "KitLook.RAISED -> DeleteButtonView")),
            "RAISED to Button3dView",
        )
        assertFires(
            "the danger look line removed",
            kitProblems(sample.replace("KitLook.DANGER -> DeleteButtonView(context, text, theme)", "")),
            "DANGER to DeleteButtonView",
        )
        assertFires(
            "the plain button removed",
            kitProblems(sample.replace("null -> Button(context)", "null -> Empty")),
            "plain Button",
        )
        assertQuiet("the mapping as written", kitProblems(sample))
        assertQuiet("the real renderer", kitProblems(realFile(RENDERER)))
    }
}
