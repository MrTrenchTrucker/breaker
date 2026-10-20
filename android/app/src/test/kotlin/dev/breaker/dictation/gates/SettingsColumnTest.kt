package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the settings column content and the ROUTE_MODEL focus, because no JVM test
 * can run the activity. The settings column holds ONLY the settings view: no
 * onboarding walk-through, no download status line, no History button. The
 * ROUTE_MODEL focus lands on the settings view's model download control.
 *
 * The rules read the real activity file with comments and literal text removed
 * by the shared scanner; each rule holds on the real file, is broken by at least
 * two edited samples, and stays quiet on harmless edits.
 */
internal class SettingsColumnTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    private class Rule(val name: String, val pattern: String, val times: Int = 1) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private class Quiet(val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("COLUMN_HOLDS_ONLY_THE_SETTINGS_VIEW", """\bcolumn\s*\.\s*addView\s*\(\s*settingsView\s*,\s*LinearLayout\s*\.\s*LayoutParams\s*\(\s*ViewGroup\s*\.\s*LayoutParams\s*\.\s*MATCH_PARENT\s*,\s*ViewGroup\s*\.\s*LayoutParams\s*\.\s*WRAP_CONTENT\s*\)\s*\)"""),
        Rule("NO_ONBOARDING_IN_THE_COLUMN", """\bcolumn\s*\.\s*addView\s*\(\s*onboarding\s*\)""", 0),
        Rule("NO_DOWNLOAD_STATUS_IN_THE_COLUMN", """\bcolumn\s*\.\s*addView\s*\(\s*downloadStatus\s*\)""", 0),
        Rule("NO_HISTORY_BUTTON_IN_THE_COLUMN", """\bcolumn\s*\.\s*addView\s*\(\s*history\s*\)""", 0),
        Rule("NO_BUTTON_IN_THE_ACTIVITY", """\bButton\s*\(""", 0),
        /** The route opens Settings, then focuses its download control. */
        Rule("MODEL_ROUTE_FOCUSES_THE_SETTINGS_DOWNLOAD", """\bgetStringExtra\s*\(\s*NotificationRoute\s*\.\s*EXTRA_ROUTE\s*\)\s*==\s*NotificationRoute\s*\.\s*ROUTE_MODEL\s*\)\s*\{\s*openSettings\s*\(\s*\)\s*\(settingsView\s+as\?\s+dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*ui\s*\.\s*DownloadControl\s*\)\s*\?\s*\.\s*focusDownload\s*\(\s*\)\s*\}"""),
        Rule("DOWNLOAD_STATE_WIRES_TO_THE_SETTINGS_VIEW", """\boverride\s+fun\s+downloading\s*\(\s*\w+\s*:\s*String\s*\)\s*\{[^}]*downloadControlOf\s*\(\s*firstRunShown\s*,\s*firstRunView\s*,\s*settingsView\s*\)\s*\?\s*\.\s*downloadProgress\s*\(\s*0\s*,\s*0\s*\)\s*\}"""),
        Rule("SETTINGS_VIEW_IS_CALLED_ON_DONE", """\boverride\s+fun\s+done\s*\(\s*\)\s*\{[^}]*downloadControlOf\s*\(\s*firstRunShown\s*,\s*firstRunView\s*,\s*settingsView\s*\)\s*\?\s*\.\s*downloadStopped\s*\(\s*\)\s*\}"""),
        Rule("SETTINGS_VIEW_IS_CALLED_ON_FAILED", """\boverride\s+fun\s+failed\s*\(\s*sentence\s*:\s*String\s*\)\s*\{[^}]*downloadControlOf\s*\(\s*firstRunShown\s*,\s*firstRunView\s*,\s*settingsView\s*\)\s*\?\s*\.\s*downloadStopped\s*\(\s*\)\s*\}"""),
        Rule("SETTINGS_VIEW_IS_CALLED_ON_PROGRESS", """\boverride\s+fun\s+progress\s*\(\s*sentence\s*:\s*String\s*,\s*doneMb\s*:\s*Int\s*,\s*totalMb\s*:\s*Int\s*\)\s*\{[\s\S]*control\s*\?\s*\.\s*downloadProgress\s*\(\s*doneMb\s*,\s*totalMb\s*\)\s*\}"""),
        Rule("SETTINGS_VIEW_IS_CALLED_ON_CHECKING", """\boverride\s+fun\s+progress\s*\(\s*sentence\s*:\s*String\s*,\s*doneMb\s*:\s*Int\s*,\s*totalMb\s*:\s*Int\s*\)\s*\{[^}]*control\s*\?\s*\.\s*downloadChecking\s*\(\s*\)\s*\}"""),
    )

    private val firing: List<Sample> = listOf(
        Sample("COLUMN_HOLDS_ONLY_THE_SETTINGS_VIEW", "column.addView(settingsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))", "column.addView(onboarding)"),
        Sample("COLUMN_HOLDS_ONLY_THE_SETTINGS_VIEW", "column.addView(settingsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))", "column.addView(downloadStatus)"),
        Sample("NO_ONBOARDING_IN_THE_COLUMN", "column.addView(settingsView", "column.addView(onboarding)"),
        Sample("NO_ONBOARDING_IN_THE_COLUMN", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.VERTICAL\n        column.addView(onboarding)"),
        Sample("NO_DOWNLOAD_STATUS_IN_THE_COLUMN", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.VERTICAL\n        column.addView(downloadStatus)"),
        Sample("NO_DOWNLOAD_STATUS_IN_THE_COLUMN", "column.addView(settingsView, LinearLayout", "column.addView(downloadStatus)\n        column.addView(settingsView, LinearLayout"),
        Sample("NO_HISTORY_BUTTON_IN_THE_COLUMN", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.VERTICAL\n        column.addView(history)"),
        Sample("NO_HISTORY_BUTTON_IN_THE_COLUMN", "settingsColumn = column", "settingsColumn = column\n        column.addView(history)"),
        Sample("NO_BUTTON_IN_THE_ACTIVITY", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.VERTICAL\n        val extra = Button(this)"),
        Sample("NO_BUTTON_IN_THE_ACTIVITY", "settingsScroll = scroll", "settingsScroll = scroll\n        val extra = Button(this)"),
        Sample("MODEL_ROUTE_FOCUSES_THE_SETTINGS_DOWNLOAD", "(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.focusDownload()", "settingsView.refresh()"),
        Sample("MODEL_ROUTE_FOCUSES_THE_SETTINGS_DOWNLOAD", "NotificationRoute.ROUTE_MODEL) {", "NotificationRoute.ROUTE_HISTORY) {"),
        Sample("DOWNLOAD_STATE_WIRES_TO_THE_SETTINGS_VIEW", "downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadProgress(0, 0)", "downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadProgress(1, 1)"),
        Sample("DOWNLOAD_STATE_WIRES_TO_THE_SETTINGS_VIEW", "override fun downloading(sentence: String) {", "override fun downloadingLater(sentence: String) {"),
        Sample("SETTINGS_VIEW_IS_CALLED_ON_DONE", "override fun done() {\n        downloadSentence = null\n        downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadStopped()", "override fun done() {\n        downloadSentence = null\n        settingsView.refresh()"),
        Sample("SETTINGS_VIEW_IS_CALLED_ON_DONE", "override fun done() {", "override fun doneLater() {"),
        Sample("SETTINGS_VIEW_IS_CALLED_ON_FAILED", "override fun failed(sentence: String) {\n        downloadSentence = sentence\n        downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadStopped()", "override fun failed(sentence: String) {\n        downloadSentence = sentence"),
        Sample("SETTINGS_VIEW_IS_CALLED_ON_FAILED", "override fun failed(sentence: String) {", "override fun failedLater(sentence: String) {"),
        Sample("SETTINGS_VIEW_IS_CALLED_ON_PROGRESS", "control?.downloadProgress(doneMb, totalMb)", "control?.downloadProgress(0, 0)"),
        Sample("SETTINGS_VIEW_IS_CALLED_ON_PROGRESS", "override fun progress(sentence: String, doneMb: Int, totalMb: Int) {", "override fun progressLater(sentence: String, doneMb: Int, totalMb: Int) {"),
        Sample("SETTINGS_VIEW_IS_CALLED_ON_CHECKING", "control?.downloadChecking()", "(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.downloadProgress(0, 0)"),
        Sample("SETTINGS_VIEW_IS_CALLED_ON_CHECKING", "control?.downloadChecking()", "control?.downloadStopped()"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("column.addView(settingsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))", "column.addView(  settingsView  , LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))"),
        Quiet("downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadProgress(0, 0)", "downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadProgress( 0 , 0 )"),
        Quiet("downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadStopped()", "downloadControlOf(firstRunShown, firstRunView, settingsView)?.downloadStopped( )"),
        Quiet("(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.focusDownload()", "(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.focusDownload( )"),
        Quiet("override fun downloading(sentence: String) {", "override fun downloading( sentence: String ) {"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a settings column sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(activity)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `every rule holds on the real activity`() {
        val code = codeOf()
        val broken = rules.filter { !it.holds(code) }.map { it.name }
        assertTrue("activity breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `the rules fire on their edited samples`() {
        val byName = rules.associateBy { it.name }
        val unfired = firing
            .filter { byName.getValue(it.rule).holds(codeOf(it.old, it.new)) }
            .map { "${it.rule}: '${it.old}' => '${it.new}'" }
        assertTrue("app: samples that leave their rule true: $unfired", unfired.isEmpty())
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a rule name is used twice", names.toSet().size, names.size)
        val unknown = firing.map { it.rule }.filter { it !in names }
        assertTrue("app: samples of unknown rules: $unknown", unknown.isEmpty())
        val thin = names.filter { name -> firing.count { it.rule == name } < 2 }
        assertTrue("app: rules with fewer than two firing samples: $thin", thin.isEmpty())
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        val broken = quiet.flatMap { q ->
            val code = codeOf(q.old, q.new)
            rules.filter { !it.holds(code) }.map { "${it.name} on '${q.old}' => '${q.new}'" }
        }
        assertTrue("app: a harmless edit broke a rule: $broken", broken.isEmpty())
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }

    @Test
    fun `a History button added back to the settings column breaks a rule`() {
        // An edited copy: a History button is added back to the settings column.
        // This test must fail on the edited copy.
        val withHistory = edit(codeOf(), "column.addView(settingsView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))", "val history = Button(this)\n        onHistory = { openHistory() }\n        column.addView(history)")
        val broken = rules.filter { !it.holds(withHistory) }.map { it.name }
        assertTrue("a History button added back must break a rule, but these rules still hold: ${rules.map { it.name }.minus(broken)}", broken.isNotEmpty())
    }
}
