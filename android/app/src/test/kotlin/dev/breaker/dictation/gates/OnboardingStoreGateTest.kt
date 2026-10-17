package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher hands the walk-through the app's own settings store as its last argument.
 *
 * The walk-through keeps its microphone flag in that store, so the launcher must pass the
 * app's store itself, not a copy and not another object. The rule reads the real launcher
 * activity with comments and literal text removed by the shared scanner. Each sample is the
 * real text with one edit, and the rule must fire on it.
 */
internal class OnboardingStoreGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** The walk-through call, then the store as its argument, then the closing parenthesis. */
    private val rule = Regex("""\bcreateOnboardingView\s*\([\s\S]*?\bsettings\s*=\s*app\s*\.\s*settingsStore\s*,?\s*\)""")

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a store check sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(activity)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `the walk-through is handed the app's settings store on the real launcher`() {
        assertTrue("app: the launcher source was read as empty", codeOf().isNotBlank())
        assertTrue("app: the launcher does not pass settings = app.settingsStore to the walk-through", rule.containsMatchIn(codeOf()))
    }

    @Test
    fun `a walk-through call without the app's store, with another value or with an empty object is caught`() {
        assertFalse("app: the store argument is missing", rule.containsMatchIn(codeOf("settings = app.settingsStore,", "")))
        assertFalse("app: the store argument is another value", rule.containsMatchIn(codeOf("settings = app.settingsStore,", "settings = app.armedSwitch,")))
        assertFalse("app: the store argument is an empty object", rule.containsMatchIn(codeOf("settings = app.settingsStore,", "settings = EmptySettings(),")))
    }

    @Test
    fun `spacing and a trailing comment do not change the store argument`() {
        assertTrue(rule.containsMatchIn(codeOf("settings = app.settingsStore,", "settings = app . settingsStore ,")))
        assertTrue(rule.containsMatchIn(codeOf("settings = app.settingsStore,", "settings = app.settingsStore, // the store")))
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
