package dev.breaker.dictation.gates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The activity's settings view is set before the first-run decision.
 *
 * settingsView is a lateinit property that is read as an argument by the download helpers on
 * every path, including the first-run path, which returns before Settings is built. A read of a
 * lateinit property that was never set throws at the first resume of a fresh install and closes
 * the app. The activity sets the property to a plain view at the top of onCreate, before the
 * first-run decision; Settings later replaces it. The rule reads the code of the real activity,
 * with comments and literals blanked, and is shown to fail on the two edits listed below.
 */
internal class SettingsViewSetBeforeFirstRunTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"
    private val setLine: String = "settingsView=android.view.View(this)"
    private val decision: String = "when(FirstRunGate.decide("

    /** The code of the activity with whitespace folded away, [old] changed to [new] when an edit is given. */
    private fun codeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(activity)
        val edited = if (old == null) text else {
            check(Regex(Regex.escape(old)).findAll(text).count() == 1) { "app: the text to change is not in the activity exactly once: $old" }
            text.replace(old, new)
        }
        return AppSourceFiles.strip(edited).code.replace(Regex("""\s+"""), "")
    }

    /** Whether the settings view is set once, and before the first-run decision. */
    private fun holds(code: String): Boolean {
        val set = code.indexOf(setLine)
        val decide = code.indexOf(decision)
        return set >= 0 && decide > set && code.indexOf(setLine, set + 1) < 0
    }

    @Test
    fun `the real activity sets the settings view before the first run decision`() {
        assertTrue("app: the activity must set settingsView before the first-run decision", holds(codeOf()))
    }

    @Test
    fun `dropping the line fails the rule`() {
        val dropped = codeOf("        settingsView = android.view.View(this)\n", "")
        assertFalse("app: a missing early settingsView must be seen", holds(dropped))
    }

    @Test
    fun `moving the line after the decision fails the rule`() {
        val moved = codeOf(
            "        settingsView = android.view.View(this)\n",
            "",
        ).let { code ->
            // put it back after the decision, as the lazy original had it
            code.replace(decision, decision) + setLine
        }
        assertFalse("app: a settingsView set only after the decision must be seen", holds(moved))
    }
}
