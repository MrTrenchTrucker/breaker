package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the delete listener's lifecycle in the launcher activity: it is registered in onResume with the settings
 * view's refresh, right after the download listener, and cleared in onPause, right after the download listener is
 * cleared; it is set in no other place. Each rule reads the comment-stripped activity code. Each firing sample must
 * break the rule it names, and each quiet edit must keep every rule. Nothing here has been compiled or run.
 */
internal class DeleteListenerLifecycleGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    private class Rule(val name: String, val pattern: String, val times: Int = 1) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule(
            "DELETE_LISTENER_REGISTERED_IN_RESUME_RIGHT_AFTER_THE_DOWNLOAD_LISTENER",
            """\boverride\s+fun\s+onResume\s*\(\s*\)\s*\{\s*super\s*\.\s*onResume\s*\(\s*\)\s*val\s+app\s*=\s*applicationContext\s+as\s+BreakerApp\s+app\s*\.\s*tileHost\s*\.\s*setDownloadListener\s*\(\s*this\s*\)\s*app\s*\.\s*tileHost\s*\.\s*setDeleteListener\s*\(\s*dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*host\s*\.\s*redrawOnDelete\s*\{\s*settingsView\s+as\?\s+dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*ui\s*\.\s*DownloadControl\s*\}\s*\)""",
        ),
        Rule(
            "DELETE_LISTENER_CLEARED_IN_PAUSE_RIGHT_AFTER_THE_DOWNLOAD_LISTENER",
            """\boverride\s+fun\s+onPause\s*\(\s*\)\s*\{\s*super\s*\.\s*onPause\s*\(\s*\)\s*val\s+app\s*=\s*applicationContext\s+as\s+BreakerApp\s+app\s*\.\s*tileHost\s*\.\s*setDownloadListener\s*\(\s*null\s*\)\s*app\s*\.\s*tileHost\s*\.\s*setDeleteListener\s*\(\s*null\s*\)\s*app\s*\.\s*serviceStatusHome\s*\(\s*\)\s*\.\s*onPause\s*\(\s*\)\s*\}""",
        ),
        Rule("DELETE_LISTENER_IS_SET_ONLY_IN_RESUME_AND_PAUSE", """\bsetDeleteListener\s*\(""", times = 2),
    )

    private val samples: List<Sample> = listOf(
        Sample(
            "DELETE_LISTENER_REGISTERED_IN_RESUME_RIGHT_AFTER_THE_DOWNLOAD_LISTENER",
            "app.tileHost.setDeleteListener(dev.breaker.dictation.host.redrawOnDelete { settingsView as? dev.breaker.dictation.ui.DownloadControl })",
            "app.tileHost.setDeleteListener(dev.breaker.dictation.host.redrawOnDelete { null })",
        ),
        Sample("DELETE_LISTENER_REGISTERED_IN_RESUME_RIGHT_AFTER_THE_DOWNLOAD_LISTENER", "override fun onResume() {", "override fun onResumeLater() {"),
        Sample(
            "DELETE_LISTENER_REGISTERED_IN_RESUME_RIGHT_AFTER_THE_DOWNLOAD_LISTENER",
            "app.tileHost.setDownloadListener(this)\n        app.tileHost.setDeleteListener(",
            "app.tileHost.setDownloadListener(this)\n        app.serviceStatusHome().hashCode()\n        app.tileHost.setDeleteListener(",
        ),
        Sample("DELETE_LISTENER_CLEARED_IN_PAUSE_RIGHT_AFTER_THE_DOWNLOAD_LISTENER", "app.tileHost.setDeleteListener(null)", "app.tileHost.setDeleteListener(this)"),
        Sample(
            "DELETE_LISTENER_CLEARED_IN_PAUSE_RIGHT_AFTER_THE_DOWNLOAD_LISTENER",
            "app.tileHost.setDownloadListener(null)\n        app.tileHost.setDeleteListener(null)",
            "app.tileHost.setDeleteListener(null)\n        app.tileHost.setDownloadListener(null)",
        ),
        Sample("DELETE_LISTENER_CLEARED_IN_PAUSE_RIGHT_AFTER_THE_DOWNLOAD_LISTENER", "override fun onPause() {", "override fun onPauseLater() {"),
        Sample("DELETE_LISTENER_IS_SET_ONLY_IN_RESUME_AND_PAUSE", "app.serviceStatusHome().onPause()", "app.tileHost.setDeleteListener(null)\n        app.serviceStatusHome().onPause()"),
        Sample(
            "DELETE_LISTENER_IS_SET_ONLY_IN_RESUME_AND_PAUSE",
            "app.tileHost.setDeleteListener(dev.breaker.dictation.host.redrawOnDelete { settingsView as? dev.breaker.dictation.ui.DownloadControl })",
            "",
        ),
    )

    private val quiet: List<Sample> = listOf(
        Sample("", "app.tileHost.setDeleteListener(null)", "app.tileHost\n            .setDeleteListener(null)"),
        Sample("", "override fun onPause() {", "// setDeleteListener(null) is the one clear\n    override fun onPause() {"),
        Sample("", "settingsView as? dev.breaker.dictation.ui.DownloadControl })", "settingsView as? dev.breaker.dictation.ui.DownloadControl\n        })"),
    )

    private val raw: String = AppSourceFiles.mainFile(activity)

    private fun occurrences(text: String, old: String): Int = Regex(Regex.escape(old)).findAll(text).count()

    /** The comment-stripped activity code, with [old] replaced by [new] once; fails by name when [old] is not exactly once. */
    private fun codeOf(old: String? = null, new: String = ""): String {
        val text: String = if (old == null) {
            raw
        } else {
            check(occurrences(raw, old) == 1) { "app: a delete listener sample must find its text exactly once: '$old'" }
            raw.replace(old, new)
        }
        return AppSourceFiles.strip(text).code
    }

    private fun ruleNamed(name: String): Rule = rules.single { it.name == name }

    @Test
    fun `the real activity holds every delete listener rule`() {
        val code = codeOf()
        for (rule in rules) {
            assertTrue("app: ${rule.name} must hold on the real activity", rule.holds(code))
        }
    }

    @Test
    fun `each firing sample breaks the rule it names`() {
        for (sample in samples) {
            assertFalse(
                "app: ${sample.rule} must fire on '${sample.old}' => '${sample.new}'",
                ruleNamed(sample.rule).holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `each delete listener rule has at least two firing samples`() {
        for (rule in rules) {
            val count = samples.count { it.rule == rule.name }
            assertTrue("app: ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `each quiet edit of the activity keeps every delete listener rule`() {
        for (edit in quiet) {
            val code = codeOf(edit.old, edit.new)
            for (rule in rules) {
                assertTrue("app: ${rule.name} must stay quiet on '${edit.old}' => '${edit.new}'", rule.holds(code))
            }
        }
    }

    @Test
    fun `the delete listener is set twice in the real activity and the count is the rule`() {
        assertEquals("app: the activity sets the delete listener in resume and in pause", 2, Regex("""\bsetDeleteListener\s*\(""").findAll(codeOf()).count())
    }
}
