package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings column holds one child, the settings view, and the activity builds no
 * stock text view or button.
 *
 * The column is the one container the settings page is put in. Each rule is a count
 * over the code of the real activity, with comments and literal text blanked first, so
 * a count sees code only. Each rule holds on the real file, fires on two edited samples,
 * and stays true on the harmless edits listed as quiet below.
 */
internal class SettingsColumnSingleChildTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** A count of matches of [pattern] in the code that must come to exactly [expected]. */
    private class Rule(val name: String, val pattern: String, val expected: Int) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == expected
    }

    /** An edit to the real activity; [rule] names the rule it is meant to break. */
    private class Sample(val rule: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("COLUMN_ADDS_ONE_CHILD", """(?:\bcolumn|settingsColumn)\s*\.\s*addView\s*\(""", 1),
        Rule("THE_ONE_CHILD_IS_THE_SETTINGS_VIEW", """(?:\bcolumn|settingsColumn)\s*\.\s*addView\s*\(\s*settingsView\s*,""", 1),
        Rule("NO_STOCK_TEXT_OR_BUTTON_IN_THE_ACTIVITY", """\b(?:TextView|Button)\s*\(""", 0),
    )

    private val firing: List<Sample> = listOf(
        Sample("COLUMN_ADDS_ONE_CHILD", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.VERTICAL\n        column.addView(spare)"),
        Sample("COLUMN_ADDS_ONE_CHILD", "settingsColumn = column", "settingsColumn = column\n        settingsColumn.addView(spare)"),
        Sample("THE_ONE_CHILD_IS_THE_SETTINGS_VIEW", "column.addView(settingsView, ", "column.addView(spare, "),
        Sample("THE_ONE_CHILD_IS_THE_SETTINGS_VIEW", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.VERTICAL\n        column.addView(settingsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))"),
        Sample("NO_STOCK_TEXT_OR_BUTTON_IN_THE_ACTIVITY", "settingsScroll = scroll", "settingsScroll = scroll\n        val note = TextView(this)"),
        Sample("NO_STOCK_TEXT_OR_BUTTON_IN_THE_ACTIVITY", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.VERTICAL\n        val tap = Button(this)"),
    )

    /** Harmless edits: a comment that names a child, and a literal that names a stock class. */
    private val quiet: List<Sample> = listOf(
        Sample("", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.VERTICAL\n        // column.addView(spare) would be a second child"),
        Sample("", "settingsScroll = scroll", "settingsScroll = scroll\n        val label = \"TextView(\""),
    )

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun editOnce(text: String, old: String, new: String): String {
        val count = Regex(Regex.escape(old)).findAll(text).count()
        check(count == 1) { "app: the text to change is not in the activity exactly once ($count times): $old" }
        return text.replace(old, new)
    }

    /** The code of the activity, with [old] changed to [new] when an edit is given. */
    private fun codeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(activity)
        return AppSourceFiles.strip(if (old == null) text else editOnce(text, old, new)).code
    }

    @Test
    fun `every column rule holds on the real activity`() {
        val code = codeOf()
        val broken = rules.filter { !it.holds(code) }.map { it.name }
        assertTrue("app: the activity breaks column rules $broken", broken.isEmpty())
    }

    @Test
    fun `the column adds exactly one child on the real activity`() {
        val rule = rules.single { it.name == "COLUMN_ADDS_ONE_CHILD" }
        val found = Regex(rule.pattern).findAll(codeOf()).count()
        assertEquals("app: the column adds $found children, and it must add exactly one", 1, found)
    }

    @Test
    fun `each column rule fires on its edited samples`() {
        val byName = rules.associateBy { it.name }
        val unfired = firing
            .filter { byName.getValue(it.rule).holds(codeOf(it.old, it.new)) }
            .map { "${it.rule}: '${it.old}' => '${it.new}'" }
        assertTrue("app: samples that leave their rule true: $unfired", unfired.isEmpty())
    }

    @Test
    fun `each column rule has at least two firing samples`() {
        val thin = rules.map { it.name }.filter { name -> firing.count { it.rule == name } < 2 }
        assertTrue("app: rules with fewer than two firing samples: $thin", thin.isEmpty())
    }

    @Test
    fun `a harmless edit leaves every column rule true`() {
        val broken = quiet.flatMap { q ->
            val code = codeOf(q.old, q.new)
            rules.filter { !it.holds(code) }.map { "${it.name} on '${q.old}'" }
        }
        assertTrue("app: a harmless edit broke a rule: $broken", broken.isEmpty())
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            editOnce("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
