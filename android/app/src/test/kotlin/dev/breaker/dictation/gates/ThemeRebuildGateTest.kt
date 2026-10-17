package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rebuild of Home and of the setup block when Settings closes, because no JVM test can run the
 * activity. The rules read `SettingsLauncherActivity.kt` through the shared scanner, so comments and
 * literal text are not read, and they look only at the body of `closeSettings`.
 */
internal class ThemeRebuildGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** A rule with its name. [holds] reads the comment-free code of the activity. */
    private class Rule(val name: String, val holds: (String) -> Boolean)

    private val rebuildCall: Regex = Regex("""\bagain\s*\(\s*\)""")
    private val recreateCall: Regex = Regex("""\brecreate\s*\(""")

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
        Rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETUP_BLOCK") { code ->
            rebuildCall.findAll(closeSettingsBody(code)).count() >= 2
        },
        Rule("CLOSE_SETTINGS_ATTACHES_THE_TAKE_WIRING") { code ->
            closeSettingsBody(code).contains("takeWiring.attach(")
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
    fun `closing settings builds home and the setup block again`() {
        val code: String = codeOf()
        assertTrue("app: closeSettings must build Home and the setup block again", rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETUP_BLOCK").holds(code))
        assertTrue("app: closeSettings must attach the take wiring to the rebuilt Home", rule("CLOSE_SETTINGS_ATTACHES_THE_TAKE_WIRING").holds(code))
    }

    @Test
    fun `closing settings does not recreate the activity`() {
        val code: String = codeOf()
        assertTrue("app: closeSettings must keep the rebuild calls in its body", rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETUP_BLOCK").holds(code))
        assertTrue("app: closeSettings must not call recreate", rule("CLOSE_SETTINGS_NEVER_RECREATES_THE_ACTIVITY").holds(code))
    }

    @Test
    fun `a closeSettings without the rebuild breaks the rule`() {
        val lost: String = edit(AppSourceFiles.mainFile(activity), "        homeView = homeRebuilt.again()\n", "")
        val code: String = AppSourceFiles.strip(lost).code
        assertFalse("app: a closeSettings without the Home rebuild must break the rebuild rule", rule("CLOSE_SETTINGS_REBUILDS_HOME_AND_THE_SETUP_BLOCK").holds(code))
    }
}
