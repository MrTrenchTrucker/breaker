package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rebuild of Home and of the settings view when Settings closes, because no JVM test can run the
 * activity. The rules read `SettingsLauncherActivity.kt` through the shared scanner, so comments and
 * literal text are not read, and they look only at the body of `closeSettings`.
 */
internal class ThemeRebuildGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** A rule with its name. [holds] reads the comment-free code of the activity. */
    private class Rule(val name: String, val holds: (String) -> Boolean)

    private val recreateCall: Regex = Regex("""\brecreate\s*\(""")
    private val homeRebuild: Regex = Regex("""\bhomeView\s*=\s*homeRebuilt\s*\.\s*again\s*\(\s*\)""")
    private val settingsSwap: Regex =
        Regex("""\bsettingsView\s*=\s*swapInColumn\s*\(\s*settingsColumn\s*,\s*settingsView\s*,\s*settingsRebuilt\s*\.\s*again\s*\(\s*\)\s*\)""")
    private val homeRebuildThenAttach: Regex = Regex(
        """\bhomeView\s*=\s*homeRebuilt\s*\.\s*again\s*\(\s*\)\s*takeWiring\s*\.\s*attach\s*\(\s*homeView\s+as\s+dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*ui\s*\.\s*HomeControl\s*\)""",
    )

    /** The text of `private fun closeSettings()`, from its opening brace to the matching brace. Empty when absent. */
    private fun closeSettingsBody(code: String): String {
        val start: Int = code.indexOf("private fun closeSettings()")
        if (start < 0) return ""
        val open: Int = code.indexOf('{', start)
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '{') depth += 1
            if (code[i] == '}') {
                depth -= 1
                if (depth == 0) return code.substring(open, i + 1)
            }
        }
        return ""
    }

    private val rules: List<Rule> = listOf(
        Rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETTINGS_VIEW") { code ->
            homeRebuild.containsMatchIn(closeSettingsBody(code)) && settingsSwap.containsMatchIn(closeSettingsBody(code))
        },
        Rule("CLOSE_SETTINGS_ATTACHES_THE_TAKE_WIRING") { code ->
            homeRebuildThenAttach.containsMatchIn(closeSettingsBody(code))
        },
        Rule("CLOSE_SETTINGS_NEVER_RECREATES_THE_ACTIVITY") { code ->
            !recreateCall.containsMatchIn(closeSettingsBody(code))
        },
    )

    /** [old] replaced by [new] once. Fails by name when the text is missing. */
    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a theme rebuild sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile(activity)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    private fun rule(name: String): Rule = rules.single { it.name == name }

    @Test
    fun `closing settings builds home and the settings view again`() {
        val code: String = codeOf()
        assertTrue("app: closeSettings must build Home and the settings view again", rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETTINGS_VIEW").holds(code))
        assertTrue("app: closeSettings must attach the take wiring to the rebuilt Home", rule("CLOSE_SETTINGS_ATTACHES_THE_TAKE_WIRING").holds(code))
    }

    @Test
    fun `closing settings does not recreate the activity`() {
        val code: String = codeOf()
        assertTrue("app: closeSettings must keep the rebuild calls in its body", rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETTINGS_VIEW").holds(code))
        assertTrue("app: closeSettings must not call recreate", rule("CLOSE_SETTINGS_NEVER_RECREATES_THE_ACTIVITY").holds(code))
    }

    @Test
    fun `a closeSettings without the rebuild breaks the rule`() {
        val lost: String = edit(AppSourceFiles.mainFile(activity), "        homeView = homeRebuilt.again()\n", "")
        val code: String = AppSourceFiles.strip(lost).code
        assertFalse("app: a closeSettings without the Home rebuild must break the rebuild rule", rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETTINGS_VIEW").holds(code))
    }

    @Test
    fun `a closeSettings that swaps one holder for the other breaks the rebuild rule`() {
        val text: String = AppSourceFiles.mainFile(activity)
        val homeFromSettings: String = edit(text, "        homeView = homeRebuilt.again()\n", "        homeView = settingsRebuilt.again()\n")
        val settingsFromHome: String =
            edit(text, "swapInColumn(settingsColumn, settingsView, settingsRebuilt.again())", "swapInColumn(settingsColumn, settingsView, homeRebuilt.again())")
        assertFalse("app: the Home rebuild must read the Home holder", rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETTINGS_VIEW").holds(AppSourceFiles.strip(homeFromSettings).code))
        assertFalse("app: the settings swap must read the settings holder", rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETTINGS_VIEW").holds(AppSourceFiles.strip(settingsFromHome).code))
    }

    @Test
    fun `a take wiring attach behind a false guard breaks the attach rule`() {
        val text: String = AppSourceFiles.mainFile(activity)
        val guarded: String =
            edit(text, "        takeWiring.attach(homeView as dev.breaker.dictation.ui.HomeControl)\n", "        if (false) takeWiring.attach(homeView as dev.breaker.dictation.ui.HomeControl)\n")
        assertFalse("app: an attach behind a false guard must break the attach rule", rule("CLOSE_SETTINGS_ATTACHES_THE_TAKE_WIRING").holds(AppSourceFiles.strip(guarded).code))
    }
}
