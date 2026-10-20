package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Home hosting the activity was ruled to build: Home as the content view, the old column as
 * what SETTINGS opens, the activity passing a HOW IT WORKS and an ABOUT callback into the Home view,
 * the activity returning Home from every overlay (settings, history and the info screens), and the
 * clipboard written on the main thread.
 *
 * The clipboard itself lives in [helpers], the platform layer the activity delegates to, so the two
 * rules about it are read there and the rest are read from the activity.
 *
 * Anchored to `AppWiringGateTest`: both read the real activity source and check its wiring patterns.
 */
internal class AppHostingGateTest {

    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** The platform helper the activity delegates its clipboard to. */
    private val helpers: String = "kotlin/dev/breaker/dictation/host/ActivityHelpers.kt"

    private class Rule(val name: String, val pattern: String, val times: Int = 1, val file: String? = null) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
        fun file(): String = file ?: "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"
    }

    private class Sample(val rule: String, val old: String, val new: String)

    private class Quiet(val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("ACTIVITY_BUILDS_HOME_VIEW", """\bcreateHomeView\s*\("""),
        Rule("ACTIVITY_SWAPS_OPEN_SETTINGS", """\bonSettings\s*=\s*\{\s*openSettings\s*\(\)\s*\}"""),
        Rule("ACTIVITY_RETURNS_HOME_FROM_EVERY_OVERLAY", """\bsetContentView\s*\(\s*homeView\s*\)""", times = 3),
        Rule("ACTIVITY_PASSES_HOW_IT_WORKS_TO_THE_HOME_VIEW", """\bonHowItWorks\s*=\s*\{"""),
        Rule("ACTIVITY_PASSES_ABOUT_TO_THE_HOME_VIEW", """\bonAbout\s*=\s*\{"""),
        Rule("ACTIVITY_WIRES_ON_COPY", """\bonCopy\s*=\s*\{\s*sentence\s*->\s*copyToClip\s*\(\s*this\s*,\s*sentence\s*\)"""),
        Rule("ACTIVITY_WRITES_THE_CLIPBOARD", """\bsetPrimaryClip\s*\(\s*android\.content\.ClipData\.newPlainText\s*\([^)]*,\s*text\s*\)""", file = helpers),
        Rule("ACTIVITY_REACHES_THE_CLIPBOARD_MANAGER", """\bgetSystemService\s*\(\s*Context\.CLIPBOARD_SERVICE\s*\)\s+as\s+android\.content\.ClipboardManager""", file = helpers),
        Rule("ACTIVITY_KEEPS_DOWNLOAD_NOTICE", """setDownloadListener\(this\)"""),
        Rule("ACTIVITY_HAS_NO_NOTICE_CONTROL", """\bHomeNoticeControl\b""", times = 0),
    )

    /** Harmless edits to the activity: each keeps every rule true. Comments, literal text and spacing only. */
    private val quiet: List<Quiet> = listOf(
        Quiet("Rebuilt { createHomeView(", "Rebuilt { createHomeView ("),
        Quiet("onSettings = { openSettings() },", "onSettings = {\n                openSettings()\n            },"),
        Quiet("onCopy = { sentence -> copyToClip(this, sentence) },", "onCopy = {  sentence  ->  copyToClip(this, sentence) },"),
        Quiet("app.tileHost.setDownloadListener(this)", "app.tileHost.setDownloadListener(this) // onHowItWorks"),
        Quiet("settingsRebuilt = dev.breaker.dictation.wiring.Rebuilt { dev.breaker.dictation.ui.createSettingsView(", "settingsRebuilt = dev.breaker.dictation.wiring.Rebuilt { dev.breaker.dictation.ui.createSettingsView( // the settings view"),
        Quiet("const val ICON_LAUNCH_DONE_KEY: String = \"icon-launch-done\"", "const val ICON_LAUNCH_DONE_KEY: String = \"icon-launch-done\" // the key"),
        Quiet("override fun onStart() {", "// onAbout and onHowItWorks are only words in this comment\n    override fun onStart() {"),
    )

    /** Harmless edits to the helper that writes the clipboard: each keeps every rule true. */
    private val quietHelpers: List<Quiet> = listOf(
        Quiet(
            "clipboard.setPrimaryClip(android.content.ClipData.newPlainText(\"Breaker\", text))",
            "clipboard.setPrimaryClip(android.content.ClipData.newPlainText(\"Breaker app\", text))",
        ),
        Quiet(
            "getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager",
            "getSystemService(Context.CLIPBOARD_SERVICE)\n        as android.content.ClipboardManager",
        ),
    )

    /** Each sample edits a file so that the rule it names stops holding. */
    private val firing: List<Sample> = listOf(
        Sample("ACTIVITY_BUILDS_HOME_VIEW", "createHomeView(", "createOnboardingView("),
        Sample("ACTIVITY_BUILDS_HOME_VIEW", "createHomeView(", "createHomeView2("),
        Sample("ACTIVITY_SWAPS_OPEN_SETTINGS", "onSettings = { openSettings() },", "onSettings = { showSettings() },"),
        Sample("ACTIVITY_SWAPS_OPEN_SETTINGS", "onSettings = { openSettings() },", "onSettings = { },"),
        Sample("ACTIVITY_RETURNS_HOME_FROM_EVERY_OVERLAY", "setContentView(homeView); applyInsets(homeView!!)", "setContentView(settingsScroll); applyInsets(settingsScroll)"),
        Sample("ACTIVITY_RETURNS_HOME_FROM_EVERY_OVERLAY", "unregisterBack()\n            setContentView(homeView)\n            applyInsets(homeView!!)", "unregisterBack()"),
        Sample("ACTIVITY_RETURNS_HOME_FROM_EVERY_OVERLAY", "setContentView(homeView)\n        applyInsets(homeView!!)", "setContentView(settingsScroll)\n        applyInsets(settingsScroll)"),
        Sample(
            "ACTIVITY_RETURNS_HOME_FROM_EVERY_OVERLAY",
            "        setContentView(homeView)\n        applyInsets(homeView!!)",
            "",
        ),
        Sample(
            "ACTIVITY_RETURNS_HOME_FROM_EVERY_OVERLAY",
            "unregisterBack()\n            setContentView(homeView)\n            applyInsets(homeView!!)",
            "unregisterBack()",
        ),
        Sample("ACTIVITY_PASSES_HOW_IT_WORKS_TO_THE_HOME_VIEW", "onHowItWorks = { openHowItWorks() },", ""),
        Sample("ACTIVITY_PASSES_HOW_IT_WORKS_TO_THE_HOME_VIEW", "onHowItWorks = { openHowItWorks() }", "onHowItWorks = openHowItWorks()"),
        Sample("ACTIVITY_PASSES_ABOUT_TO_THE_HOME_VIEW", "onAbout = { openAbout() },", ""),
        Sample("ACTIVITY_PASSES_ABOUT_TO_THE_HOME_VIEW", "onAbout = { openAbout() }", "onAbout = openAbout()"),
        Sample("ACTIVITY_WIRES_ON_COPY", "onCopy = { sentence -> copyToClip(this, sentence) },", "onCopy = { sentence -> },"),
        Sample("ACTIVITY_WIRES_ON_COPY", "onCopy = { sentence -> copyToClip(this, sentence) },", "onCopy = { text -> copyToClip(this, text) },"),
        Sample(
            "ACTIVITY_WRITES_THE_CLIPBOARD",
            "clipboard.setPrimaryClip(android.content.ClipData.newPlainText(\"Breaker\", text))",
            "clipboard.setPrimaryClip(clipboard.primaryClip)",
        ),
        Sample(
            "ACTIVITY_WRITES_THE_CLIPBOARD",
            "clipboard.setPrimaryClip(android.content.ClipData.newPlainText(\"Breaker\", text))",
            "",
        ),
        Sample("ACTIVITY_REACHES_THE_CLIPBOARD_MANAGER", "getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager", "getSystemService(Context.CLIPBOARD_SERVICE) as Any"),
        Sample("ACTIVITY_REACHES_THE_CLIPBOARD_MANAGER", "getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager", ""),
        Sample("ACTIVITY_KEEPS_DOWNLOAD_NOTICE", "app.tileHost.setDownloadListener(this)", "app.tileHost.setDownloadListener(null)"),
        Sample("ACTIVITY_KEEPS_DOWNLOAD_NOTICE", "app.tileHost.setDownloadListener(this)", "app.tileHost.setDownloadListener(app)"),
        Sample("ACTIVITY_HAS_NO_NOTICE_CONTROL", "onHistory = { openHistory() },", "onHistory = { openHistory() },\n            HomeNoticeControl"),
        Sample("ACTIVITY_HAS_NO_NOTICE_CONTROL", "override fun onStart() {", "override fun onStart() {\n        HomeNoticeControl"),
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

    private fun helperCodeOf(old: String? = null, new: String = ""): String {
        val text = AppSourceFiles.mainFile(helpers)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `no rule reads a doubled backslash`() {
        val doubled = rules.filter { it.pattern.contains("\\\\") }.map { it.name }
        assertTrue("app: rules that read a doubled backslash match nothing: $doubled", doubled.isEmpty())
    }

    @Test
    fun `every rule holds on the real activity and its helper`() {
        val broken = rules.filter { rule ->
            val code = if (rule.file() == helpers) helperCodeOf() else codeOf()
            !rule.holds(code)
        }.map { it.name }
        assertTrue("activity breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `the rules fire on their edited samples`() {
        val byName = rules.associateBy { it.name }
        val unfired = firing.filter { sample ->
            val rule = byName.getValue(sample.rule)
            val code = if (rule.file() == helpers) helperCodeOf(sample.old, sample.new) else codeOf(sample.old, sample.new)
            rule.holds(code)
        }.map { "${it.rule}: '${it.old}' => '${it.new}'" }
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
            rules.filter { rule -> rule.file() == activity && !rule.holds(code) }.map { "${it.name} on '${q.old}' => '${q.new}'" }
        } + quietHelpers.flatMap { q ->
            val code = helperCodeOf(q.old, q.new)
            rules.filter { rule -> rule.file() == helpers && !rule.holds(code) }.map { "${it.name} on '${q.old}' => '${q.new}'" }
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
