package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher hands the settings view the app's own settings store, wrapped by the probe refresher.
 *
 * The settings view keeps its settings in that store, so the launcher must pass the app's store
 * itself (inside the decorator that refreshes the connectivity probe after a changed server address),
 * not a copy and not another object. Two rules read the real launcher with comments and literal text
 * removed by the shared scanner: the view is called with the decorator, and the decorator is built
 * over `app.settingsStore` with the root's background refresh as its callback. Each sample is the real
 * text with one edit, and the rule must fire on it.
 */
internal class SettingsStoreGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** The createSettingsView call, with the refreshing store as its second argument. */
    private val rule = Regex("""\bcreateSettingsView\s*\(\s*activity\s*,\s*refreshing\s*,""")

    /** The refreshing store: built over the app's store, calling the root's background refresh. */
    private val decoratorRule = Regex(
        """\bval\s+refreshing\s*=\s*ProbeRefreshingSettingsStore\s*\(\s*delegate\s*=\s*app\s*\.\s*settingsStore\s*,\s*""" +
            """onServerUrlChanged\s*=\s*\{\s*app\s*\.\s*compositionRoot\s*\.\s*refreshProbeInBackground\s*\(\s*\)\s*\}\s*,?\s*\)""",
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a store check sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.launcherCode()
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `the settings view is handed the refreshing store on the real launcher`() {
        assertTrue("app: the launcher source was read as empty", codeOf().isNotBlank())
        assertTrue("app: the launcher does not pass refreshing to createSettingsView", rule.containsMatchIn(codeOf()))
    }

    @Test
    fun `the refreshing store is built over the app's settings store with the root's background refresh`() {
        assertTrue("app: refreshing is not built over app.settingsStore with the root refresh", decoratorRule.containsMatchIn(codeOf()))
    }

    @Test
    fun `a settings view call without the store, with another value or with an empty object is caught`() {
        assertFalse("app: the store argument is missing", rule.containsMatchIn(codeOf("activity,\n            refreshing,", "activity,\n            ,")))
        assertFalse("app: the store argument is another value", rule.containsMatchIn(codeOf("activity,\n            refreshing,", "activity,\n            app.armedSwitch,")))
        assertFalse("app: the store argument is the bare app store", rule.containsMatchIn(codeOf("activity,\n            refreshing,", "activity,\n            app.settingsStore,")))
        assertFalse("app: the store argument is an empty object", rule.containsMatchIn(codeOf("activity,\n            refreshing,", "activity,\n            EmptySettings(),")))
    }

    @Test
    fun `a refreshing store over another store, an empty object or a dead callback is caught`() {
        assertFalse("app: the delegate is another value", decoratorRule.containsMatchIn(codeOf("delegate = app.settingsStore,", "delegate = app.armedSwitch,")))
        assertFalse("app: the delegate is an empty object", decoratorRule.containsMatchIn(codeOf("delegate = app.settingsStore,", "delegate = EmptySettings(),")))
        assertFalse("app: the callback does nothing", decoratorRule.containsMatchIn(codeOf("{ app.compositionRoot.refreshProbeInBackground() }", "{ }")))
        assertFalse("app: the callback calls another function", decoratorRule.containsMatchIn(codeOf("refreshProbeInBackground()", "sessionLive")))
        assertFalse("app: the decorator is not built", decoratorRule.containsMatchIn(codeOf("ProbeRefreshingSettingsStore(", "NoRefreshStore(")))
    }

    @Test
    fun `spacing and a trailing comment do not change the rules`() {
        assertTrue(rule.containsMatchIn(codeOf("activity,\n            refreshing,", "activity,\n            refreshing ,")))
        assertTrue(rule.containsMatchIn(codeOf("activity,\n            refreshing,", "activity,\n            refreshing, // the store")))
        assertTrue(decoratorRule.containsMatchIn(codeOf("delegate = app.settingsStore,", "delegate = app . settingsStore ,")))
        assertTrue(decoratorRule.containsMatchIn(codeOf("delegate = app.settingsStore,", "delegate = app.settingsStore, // the store")))
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
