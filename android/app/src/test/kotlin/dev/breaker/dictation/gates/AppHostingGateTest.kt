package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Home hosting the activity was ruled to build: Home as the content
 * view, the old column as what SETTINGS opens, the ui answering a HOW IT WORKS
 * or ABOUT tap in place (the activity passes neither callback), and the
 * clipboard written on the main thread.
 *
 * Anchored to `AppWiringGateTest`: both read the real activity source and
 * check its wiring patterns.
 */
internal class AppHostingGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    private class Rule(val name: String, val pattern: String, val times: Int = 1) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private class Quiet(val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("ACTIVITY_BUILDS_HOME_VIEW", """\bcreateHomeView\s*\("""),
        Rule("ACTIVITY_SWAPS_OPEN_SETTINGS", """\bonSettings\s*=\s*\{\s*openSettings\s*\(\)\s*\}"""),
        Rule("ACTIVITY_RETURNS_HOME_FROM_OVERLAYS", """\bsetContentView\s*\(\s*homeView\s*\)""", times = 2),
        Rule("ACTIVITY_PASSES_NO_HOW_IT_WORKS", """\bonHowItWorks\b""", times = 0),
        Rule("ACTIVITY_PASSES_NO_ABOUT", """\bonAbout\b""", times = 0),
        Rule("ACTIVITY_WIRES_ON_COPY", """\bonCopy\s*=\s*\{\s*sentence\s*->\s*copyToClipboard\s*\("""),
        Rule("ACTIVITY_WRITES_THE_CLIPBOARD", """\bsetPrimaryClip\s*\(\s*ClipData\.newPlainText\s*\("""),
        Rule("ACTIVITY_REACHES_THE_CLIPBOARD_MANAGER", """\bgetSystemService\s*\(\s*Context\.CLIPBOARD_SERVICE\s*\)\s+as\s+ClipboardManager"""),
        Rule("ACTIVITY_KEEPS_DOWNLOAD_NOTICE", """setDownloadListener\(this\)"""),
        Rule("ACTIVITY_HAS_NO_NOTICE_CONTROL", """\bHomeNoticeControl\b""", times = 0),
    )

    /** Harmless edits: each keeps every rule true. Comments, literal text and spacing only. */
    private val quiet: List<Quiet> = listOf(
        Quiet("val home = createHomeView(", "val home = createHomeView ("),
        Quiet("onSettings = { openSettings() },", "onSettings = {\n                openSettings()\n            },"),
        Quiet("onCopy = { sentence -> copyToClipboard(sentence) },", "onCopy = {  sentence  ->  copyToClipboard(sentence) },"),
        Quiet("ClipData.newPlainText(\"Breaker\", text)", "ClipData.newPlainText(\"Breaker app\", text)"),
        Quiet("as ClipboardManager", "as\n            ClipboardManager"),
        Quiet("app.tileHost.setDownloadListener(this)", "app.tileHost.setDownloadListener(this) // onHowItWorks"),
        Quiet("history.text = HISTORY_LABEL", "history.text = HISTORY_LABEL // HomeNoticeControl"),
        Quiet("const val HISTORY_LABEL: String = \"History\"", "const val HISTORY_LABEL: String = \"History onAbout\""),
        Quiet("private fun closeSettings() {", "private fun closeSettings() {\n        // setContentView(homeView) is restored below"),
        Quiet("private fun copyToClipboard(text: String) {", "// onAbout and onHowItWorks are only words in this comment\n    private fun copyToClipboard(text: String) {"),
    )

    /** Each sample edits the activity so that the rule it names stops holding. */
    private val firing: List<Sample> = listOf(
        Sample("ACTIVITY_BUILDS_HOME_VIEW", "createHomeView(", "createOnboardingView("),
        Sample("ACTIVITY_BUILDS_HOME_VIEW", "createHomeView(", "createHomeView2("),
        Sample("ACTIVITY_SWAPS_OPEN_SETTINGS", "onSettings = { openSettings() },", "onSettings = { showSettings() },"),
        Sample("ACTIVITY_SWAPS_OPEN_SETTINGS", "onSettings = { openSettings() },", "onSettings = { },"),
        Sample("ACTIVITY_RETURNS_HOME_FROM_OVERLAYS", "setContentView(homeView)\n            applyInsets(homeView!!)", "setContentView(settingsScroll)\n            applyInsets(settingsScroll)"),
        Sample(
            "ACTIVITY_RETURNS_HOME_FROM_OVERLAYS",
            "unregisterBack()\n        setContentView(homeView)\n        applyInsets(homeView!!)",
            "unregisterBack()",
        ),
        Sample(
            "ACTIVITY_RETURNS_HOME_FROM_OVERLAYS",
            "unregisterBack()\n            setContentView(homeView)\n            applyInsets(homeView!!)",
            "unregisterBack()",
        ),
        Sample("ACTIVITY_PASSES_NO_HOW_IT_WORKS", "onSettings = { openSettings() },", "onSettings = { openSettings() },\n            onHowItWorks = { },"),
        Sample("ACTIVITY_PASSES_NO_HOW_IT_WORKS", "onSettings = { openSettings() },", "onSettings = { openSettings() },\n            onHowItWorks = { openHistory() },"),
        Sample("ACTIVITY_PASSES_NO_HOW_IT_WORKS", "setContentView(home)", "setContentView(home)\n        val onHowItWorks = 1"),
        Sample("ACTIVITY_PASSES_NO_ABOUT", "onSettings = { openSettings() },", "onSettings = { openSettings() },\n            onAbout = { },"),
        Sample("ACTIVITY_PASSES_NO_ABOUT", "onSettings = { openSettings() },", "onSettings = { openSettings() },\n            onAbout = { openHistory() },"),
        Sample("ACTIVITY_PASSES_NO_ABOUT", "setContentView(home)", "setContentView(home)\n        val onAbout = 1"),
        Sample("ACTIVITY_WIRES_ON_COPY", "onCopy = { sentence -> copyToClipboard(sentence) },", "onCopy = { sentence -> },"),
        Sample("ACTIVITY_WIRES_ON_COPY", "onCopy = { sentence -> copyToClipboard(sentence) },", "onCopy = { text -> copyToClipboard(text) },"),
        Sample("ACTIVITY_WRITES_THE_CLIPBOARD", "clipboard.setPrimaryClip(ClipData.newPlainText(\"Breaker\", text))", "clipboard.setPrimaryClip(clipboard.primaryClip)"),
        Sample("ACTIVITY_WRITES_THE_CLIPBOARD", "clipboard.setPrimaryClip(ClipData.newPlainText(\"Breaker\", text))", ""),
        Sample("ACTIVITY_REACHES_THE_CLIPBOARD_MANAGER", "getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager", "getSystemService(Context.CLIPBOARD_SERVICE) as Any"),
        Sample("ACTIVITY_REACHES_THE_CLIPBOARD_MANAGER", "getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager", ""),
        Sample("ACTIVITY_KEEPS_DOWNLOAD_NOTICE", "app.tileHost.setDownloadListener(this)", "app.tileHost.setDownloadListener(null)"),
        Sample("ACTIVITY_KEEPS_DOWNLOAD_NOTICE", "app.tileHost.setDownloadListener(this)", "app.tileHost.setDownloadListener(app)"),
        Sample("ACTIVITY_HAS_NO_NOTICE_CONTROL", "onHistory = { openHistory() },", "onHistory = { openHistory() },\n            HomeNoticeControl"),
        Sample("ACTIVITY_HAS_NO_NOTICE_CONTROL", "private fun copyToClipboard(text: String) {", "private fun copyToClipboard(text: String) {\n        HomeNoticeControl"),
    )

    /** [old] replaced by [new] once. Fails by name when the text is missing or occurs twice. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a sample lost its text '$old'" }
        check(text.indexOf(old, at + 1) < 0) { "app: a sample text occurs twice '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(activity)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `no rule reads a doubled backslash`() {
        val doubled = rules.filter { it.pattern.contains("\\\\") }.map { it.name }
        assertTrue("app: rules that read a doubled backslash match nothing: $doubled", doubled.isEmpty())
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
    fun `a sample whose target text occurs twice fails by name`() {
        val thrown = assertThrows("app: a doubled target must fail", IllegalStateException::class.java) {
            edit("val a = 1; val b = 1", "= 1", "= 2")
        }
        assertTrue("app: the failure must name the doubled text, got ${thrown.message}", thrown.message!!.contains("occurs twice"))
    }
}
