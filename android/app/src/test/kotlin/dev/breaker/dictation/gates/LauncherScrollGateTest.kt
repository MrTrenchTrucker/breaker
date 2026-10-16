package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the launcher activity's scroll, insets and download-button removal, because no JVM test can
 * run the activity. The rules read `SettingsLauncherActivity.kt` with comments and literal text
 * removed by the shared scanner; each rule holds on the real file, is broken by at least two edited
 * samples, and stays quiet on harmless edits; an edit whose target text is missing fails by name.
 */
internal class LauncherScrollGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "activity" to dir + "SettingsLauncherActivity.kt",
        "insets" to dir + "wiring/Insets.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }
    /** Two patterns, each of which must match the code exactly once. */
    private fun once2(name: String, file: String, first: String, second: String) =
        Rule(name, file) { Regex(first).findAll(it).count() == 1 && Regex(second).findAll(it).count() == 1 }

    private val rules: List<Rule> = listOf(
        rx("ONBOARDING_VIEW_IS_CALLED_WITH_MODEL_READY_AND_DOWNLOAD_LINE", "activity", """\bcreateOnboardingView\s*\(\s*this\s*,\s*BreakerSwitchAdapter\s*\(\s*app\s*\.\s*armedSwitch\s*\)\s*,\s*ACCESSIBILITY_SERVICE_COMPONENT\s*,\s*modelReady\s*=\s*\{\s*modelReady\s*\.\s*isReady\s*\(\s*\)\s*\}\s*,\s*downloadLine\s*=\s*\{\s*downloadSentence\s*\}\s*,\s*modelDownloadMb\s*=\s*\{[^{}]*\}\s*,\s*onDownloadModel\s*=\s*\{\s*app\s*\.\s*tileHost\s*\.\s*requestDownload\s*\(\s*\)\s*\}"""),
        rx("MODEL_READY_IS_A_LIVE_PROVIDER", "activity", """\bmodelReady\s*=\s*\{\s*modelReady\s*\.\s*isReady\s*\(\s*\)\s*\}""", 2),
        rx("DOWNLOAD_LINE_IS_A_LIVE_PROVIDER", "activity", """\bdownloadLine\s*=\s*\{\s*downloadSentence\s*\}"""),
        rx("ON_DOWNLOAD_CALLS_REQUEST_DOWNLOAD", "activity", """\bonDownloadModel\s*=\s*\{\s*app\s*\.\s*tileHost\s*\.\s*requestDownload\s*\(\s*\)\s*\}"""),
        rx("COLUMN_IS_VERTICAL", "activity", """\bcolumn\s*\.\s*orientation\s*=\s*LinearLayout\s*\.\s*VERTICAL"""),
        rx("SETTINGS_VIEW_HAS_WRAP_CONTENT_HEIGHT", "activity", """\bcolumn\s*\.\s*addView\s*\(\s*settings\s*,\s*LinearLayout\s*\.\s*LayoutParams\s*\(\s*ViewGroup\s*\.\s*LayoutParams\s*\.\s*MATCH_PARENT\s*,\s*ViewGroup\s*\.\s*LayoutParams\s*\.\s*WRAP_CONTENT\s*\)\s*\)"""),
        rx("CONTENT_IS_A_SCROLL_VIEW", "activity", """\bScrollView\s*\(\s*this\s*\)\s+scroll\s*\.\s*addView\s*\(\s*column\s*,\s*ViewGroup\s*\.\s*LayoutParams\s*\(\s*ViewGroup\s*\.\s*LayoutParams\s*\.\s*MATCH_PARENT\s*,\s*ViewGroup\s*\.\s*LayoutParams\s*\.\s*WRAP_CONTENT\s*\)\)\s+settingsColumn\s*=\s*column\s+settingsScroll\s*=\s*scroll\b"""),
        rx("INSETS_ARE_APPLIED_ON_THE_SETTINGS_ROOT", "activity", """\bsetContentView\s*\(\s*settingsScroll\s*\)\s+applyInsets\s*\(\s*settingsScroll\s*\)""", 2),
        rx("INSETS_HELPER_IS_USED_FOR_THE_SETTINGS_ROOT", "activity", """\bapplyInsets\s*\(\s*settingsScroll\s*\)""", 2),
        rx("INSETS_ARE_APPLIED_ON_THE_HISTORY_ROOT", "activity", """\bapplyInsets\s*\(\s*historyView\s*\)"""),
        rx("PADDING_FOR_IS_CALLED", "activity", """\bpaddingFor\s*\("""),
        rx("SYSTEM_BAR_INSETS_ARE_READ", "activity", """\bWindowInsets\s*\.\s*Type\s*\.\s*systemBars\s*\(\s*\)"""),
        rx("DISPLAY_CUTOUT_INSETS_ARE_READ", "activity", """\bWindowInsets\s*\.\s*Type\s*\.\s*displayCutout\s*\(\s*\)"""),
        rx("ON_APPLY_WINDOW_INSETS_LISTENER_IS_SET", "activity", """\bsetOnApplyWindowInsetsListener\s*\{\s*v\s*,\s*insets\s*->"""),
        rx("INSETS_HELPER_EXISTS", "insets", """\bfun\s+paddingFor\s*\(\s*bars\s*:\s*Insets\s*,\s*cutout\s*:\s*Insets\s*\)\s*:\s*Padding"""),
        rx("INSETS_DATA_CLASS_EXISTS", "insets", """\bdata\s+class\s+Insets\s*\(\s*val\s+left\s*:\s*Int\s*,\s*val\s+top\s*:\s*Int\s*,\s*val\s+right\s*:\s*Int\s*,\s*val\s+bottom\s*:\s*Int\s*\)"""),
        rx("PADDING_DATA_CLASS_EXISTS", "insets", """\bdata\s+class\s+Padding\s*\(\s*val\s+left\s*:\s*Int\s*,\s*val\s+top\s*:\s*Int\s*,\s*val\s+right\s*:\s*Int\s*,\s*val\s+bottom\s*:\s*Int\s*\)"""),
        rx("PADDING_FOR_TAKES_MAX_OF_BOTH", "insets", """\bleft\s*=\s*maxOf\s*\(\s*bars\s*\.\s*left\s*,\s*cutout\s*\.\s*left\s*\),?\s*top\s*=\s*maxOf\s*\(\s*bars\s*\.\s*top\s*,\s*cutout\s*\.\s*top\s*\),?\s*right\s*=\s*maxOf\s*\(\s*bars\s*\.\s*right\s*,\s*cutout\s*\.\s*right\s*\),?\s*bottom\s*=\s*maxOf\s*\(\s*bars\s*\.\s*bottom\s*,\s*cutout\s*\.\s*bottom\s*\)"""),
        rx("DOWNLOAD_BUTTON_IS_GONE", "activity", """\bval\s+download\s*=\s*Button\s*\(\s*this\s*\)""", 0),
        rx("DOWNLOAD_LABEL_IS_GONE", "activity", """\bDOWNLOAD_LABEL\b""", 0),
        rx("DOWNLOAD_STATUS_LINE_REMAINS", "activity", """\bdownloadStatus\s*\.\s*text\s*""", 4),
        rx("STATUS_LINE_SHOWS_DOWNLOADING", "activity", """\boverride\s+fun\s+downloading\s*\(\s*\w+\s*:\s*String\s*\)\s*\{[^}]*downloadStatus\s*\.\s*text\s*=\s*sentence"""),
        rx("STATUS_LINE_SHOWS_DONE", "activity", """\boverride\s+fun\s+done\s*\(\s*\)\s*\{[^}]*downloadStatus\s*\.\s*text\s*=\s*""\s*"""),
        rx("STATUS_LINE_SHOWS_FAILED", "activity", """\boverride\s+fun\s+failed\s*\(\s*sentence\s*:\s*String\s*\)\s*\{[^}]*downloadStatus\s*\.\s*text\s*=\s*sentence[^}]*\}"""),
        rx("CLOSE_HISTORY_REBUILDS_THE_SCROLL_VIEW", "activity", """\bcloseHistory\s*\(\s*\)\s*\{[^}]*setContentView\s*\(\s*settingsScroll\s*\)\s+applyInsets\s*\(\s*settingsScroll\s*\)[^}]*\}"""),
        rx("MODEL_ROUTE_FOCUSES_THE_MODEL_STEP", "activity", """\bif\s*\(\s*intent\s*\?\s*\.\s*getStringExtra\s*\(\s*NotificationRoute\s*\.\s*EXTRA_ROUTE\s*\)\s*==\s*NotificationRoute\s*\.\s*ROUTE_MODEL\s*\)\s*\{\s*\(onboarding\s*as\s*\?\s*ModelStepControl\s*\)\s*\?\s*\.\s*focusDownload\s*\(\s*\)\s*\}"""),
        rx("ALL_PADDING_SIDES_APPLIED", "activity", """\bsetPadding\s*\(\s*padding\s*\.\s*left\s*,\s*padding\s*\.\s*top\s*,\s*padding\s*\.\s*right\s*,\s*padding\s*\.\s*bottom\s*\)"""),
        rx("INSETS_WIRED_PER_SIDE", "activity", """\bInsets\s*\(\s*bars\s*\.\s*left\s*,\s*bars\s*\.\s*top\s*,\s*bars\s*\.\s*right\s*,\s*bars\s*\.\s*bottom\s*\)"""),
    )

    private val firing: List<Sample> = listOf(
        Sample("ONBOARDING_VIEW_IS_CALLED_WITH_MODEL_READY_AND_DOWNLOAD_LINE", "ACCESSIBILITY_SERVICE_COMPONENT,\n            modelReady = { modelReady.isReady() },", "ACCESSIBILITY_SERVICE_COMPONENT,\n            modelReady = { true },"),
        Sample("ONBOARDING_VIEW_IS_CALLED_WITH_MODEL_READY_AND_DOWNLOAD_LINE", "downloadLine = { downloadSentence },", "downloadLine = { null },"),
        Sample("ONBOARDING_VIEW_IS_CALLED_WITH_MODEL_READY_AND_DOWNLOAD_LINE", "createOnboardingView(\n            this,", "createOnboardingView(\n            null,"),
        Sample("ONBOARDING_VIEW_IS_CALLED_WITH_MODEL_READY_AND_DOWNLOAD_LINE", "BreakerSwitchAdapter(app.armedSwitch),\n            ACCESSIBILITY_SERVICE_COMPONENT,", "BreakerSwitchAdapter(null),\n            ACCESSIBILITY_SERVICE_COMPONENT,"),
        Sample("ONBOARDING_VIEW_IS_CALLED_WITH_MODEL_READY_AND_DOWNLOAD_LINE", "ACCESSIBILITY_SERVICE_COMPONENT,\n            modelReady", "null,\n            modelReady"),
        Sample("ONBOARDING_VIEW_IS_CALLED_WITH_MODEL_READY_AND_DOWNLOAD_LINE", "modelDownloadMb = {", "modelDownloadSize = {"),
        Sample("ONBOARDING_VIEW_IS_CALLED_WITH_MODEL_READY_AND_DOWNLOAD_LINE", "onDownloadModel = { app.tileHost.requestDownload() }", "onDownloadModel = { }"),
        Sample("MODEL_READY_IS_A_LIVE_PROVIDER", "modelReady = { modelReady.isReady() },\n            lastDictation", "modelReady = { true },\n            lastDictation"),
        Sample("MODEL_READY_IS_A_LIVE_PROVIDER", "modelReady = { modelReady.isReady() },\n            downloadLine", "modelReady = { modelReady.isReady().also { } },\n            downloadLine"),
        Sample("DOWNLOAD_LINE_IS_A_LIVE_PROVIDER", "downloadLine = { downloadSentence }", "downloadLine = { null }"),
        Sample("DOWNLOAD_LINE_IS_A_LIVE_PROVIDER", "downloadLine = { downloadSentence }", "downloadLine = { downloadSentence + \"\" }"),
        Sample("ON_DOWNLOAD_CALLS_REQUEST_DOWNLOAD", "onDownloadModel = { app.tileHost.requestDownload() }", "onDownloadModel = { }"),
        Sample("ON_DOWNLOAD_CALLS_REQUEST_DOWNLOAD", "onDownloadModel = { app.tileHost.requestDownload() }", "onDownloadModel = { app.tileHost.openLauncher(null) }"),
        Sample("COLUMN_IS_VERTICAL", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.HORIZONTAL"),
        Sample("COLUMN_IS_VERTICAL", "LinearLayout.VERTICAL", "LinearLayout.HORIZONTAL"),
        Sample("SETTINGS_VIEW_HAS_WRAP_CONTENT_HEIGHT", "LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))", "LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))"),
        Sample("SETTINGS_VIEW_HAS_WRAP_CONTENT_HEIGHT", "column.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))", "column.addView(settings)"),
        Sample("CONTENT_IS_A_SCROLL_VIEW", "ScrollView(this)", "LinearLayout(this)"),
        Sample("CONTENT_IS_A_SCROLL_VIEW", "scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))", "scroll.addView(column)"),
        Sample("INSETS_ARE_APPLIED_ON_THE_SETTINGS_ROOT", "applyInsets(settingsScroll)\n        registerBack()", "registerBack()"),
        Sample("INSETS_ARE_APPLIED_ON_THE_SETTINGS_ROOT", "applyInsets(settingsScroll)\n        } else {", "} else {"),
        Sample("INSETS_ARE_APPLIED_ON_THE_HISTORY_ROOT", "applyInsets(historyView)", ""),
        Sample("INSETS_ARE_APPLIED_ON_THE_HISTORY_ROOT", "applyInsets(historyView)", "historyView.setPadding(0, 0, 0, 0)"),
        Sample("INSETS_HELPER_IS_USED_FOR_THE_SETTINGS_ROOT", "applyInsets(settingsScroll)\n        registerBack()", "registerBack()"),
        Sample("INSETS_HELPER_IS_USED_FOR_THE_SETTINGS_ROOT", "applyInsets(settingsScroll)\n        } else {", "} else {"),
        Sample("PADDING_FOR_IS_CALLED", "paddingFor(", "maxOf("),
        Sample("PADDING_FOR_IS_CALLED", "paddingFor(", "computePadding("),
        Sample("SYSTEM_BAR_INSETS_ARE_READ", "WindowInsets.Type.systemBars()", "WindowInsets.Type.navigationBars()"),
        Sample("SYSTEM_BAR_INSETS_ARE_READ", "WindowInsets.Type.systemBars()", "WindowInsets.Type.captionBar()"),
        Sample("DISPLAY_CUTOUT_INSETS_ARE_READ", "WindowInsets.Type.displayCutout()", "WindowInsets.Type.systemGestures()"),
        Sample("DISPLAY_CUTOUT_INSETS_ARE_READ", "WindowInsets.Type.displayCutout()", "WindowInsets.Type.ime()"),
        Sample("ON_APPLY_WINDOW_INSETS_LISTENER_IS_SET", "setOnApplyWindowInsetsListener { v, insets ->", ""),
        Sample("ON_APPLY_WINDOW_INSETS_LISTENER_IS_SET", "setOnApplyWindowInsetsListener { v, insets ->", "// no listener"),
        Sample("INSETS_HELPER_EXISTS", "fun paddingFor(bars: Insets, cutout: Insets): Padding", "fun paddingFor(bars: Insets, cutout: Insets): Margins"),
        Sample("INSETS_HELPER_EXISTS", "fun paddingFor(bars: Insets, cutout: Insets): Padding", "fun computePadding(bars: Insets, cutout: Insets): Padding"),
        Sample("INSETS_DATA_CLASS_EXISTS", "data class Insets(", "data class WindowInsets("),
        Sample("INSETS_DATA_CLASS_EXISTS", "data class Insets(", "class Insets("),
        Sample("PADDING_DATA_CLASS_EXISTS", "data class Padding(", "data class ViewPadding("),
        Sample("PADDING_DATA_CLASS_EXISTS", "data class Padding(", "class Padding("),
        Sample("PADDING_FOR_TAKES_MAX_OF_BOTH", "left = maxOf(bars.left, cutout.left)", "left = bars.left"),
        Sample("PADDING_FOR_TAKES_MAX_OF_BOTH", "top = maxOf(bars.top, cutout.top)", "top = bars.top"),
        Sample("DOWNLOAD_BUTTON_IS_GONE", "column.addView(onboarding)", "val download = Button(this)\n        column.addView(download)\n        column.addView(onboarding)"),
        Sample("DOWNLOAD_BUTTON_IS_GONE", "column.addView(onboarding)", "val download = Button(this)\n        column.addView(onboarding)"),
        Sample("DOWNLOAD_LABEL_IS_GONE", "const val HISTORY_LABEL", "const val DOWNLOAD_LABEL\n        const val HISTORY_LABEL"),
        Sample("DOWNLOAD_LABEL_IS_GONE", "HISTORY_LABEL: String = \"History\"", "DOWNLOAD_LABEL: String = \"Download\"\n        HISTORY_LABEL: String = \"History\""),
        Sample("DOWNLOAD_STATUS_LINE_REMAINS", "override fun downloading(sentence: String) {\n        downloadSentence = sentence\n        downloadStatus.text = sentence", "override fun downloading(sentence: String) {\n        downloadSentence = sentence"),
        Sample("DOWNLOAD_STATUS_LINE_REMAINS", "override fun failed(sentence: String) {\n        downloadSentence = sentence\n        downloadStatus.text = sentence", "override fun failed(sentence: String) {\n        downloadSentence = sentence"),
        Sample("STATUS_LINE_SHOWS_DOWNLOADING", "override fun downloading(sentence: String) {\n        downloadSentence = sentence\n        downloadStatus.text = sentence", "override fun downloading(sentence: String) {\n        downloadSentence = sentence\n        downloadStatus.text = \"\""),
        Sample("STATUS_LINE_SHOWS_DOWNLOADING", "override fun downloading(sentence: String) {", "override fun downloadingLater(sentence: String) {"),
        Sample("STATUS_LINE_SHOWS_DONE", "override fun done() {", "override fun doneLater() {"),
        Sample("STATUS_LINE_SHOWS_DONE", "downloadSentence = null\n        downloadStatus.text = \"\"", "downloadSentence = null\n        downloadStatus.text = sentence"),
        Sample("STATUS_LINE_SHOWS_FAILED", "override fun failed(sentence: String) {\n        downloadSentence = sentence\n        downloadStatus.text = sentence", "override fun failed(sentence: String) {\n        downloadSentence = sentence\n        downloadStatus.text = \"\""),
        Sample("STATUS_LINE_SHOWS_FAILED", "override fun failed(sentence: String) {", "override fun failedLater(sentence: String) {"),
        Sample("CLOSE_HISTORY_REBUILDS_THE_SCROLL_VIEW", "if (settingsShown) {\n            setContentView(settingsScroll)", "if (settingsShown) {\n            LinearLayout(this)"),
        Sample("CLOSE_HISTORY_REBUILDS_THE_SCROLL_VIEW", "applyInsets(settingsScroll)\n        } else {", "} else {"),
        Sample("MODEL_ROUTE_FOCUSES_THE_MODEL_STEP", "(onboarding as? ModelStepControl)?.focusDownload()", "(onboarding as? ModelStepControl)?.refresh()"),
        Sample("MODEL_ROUTE_FOCUSES_THE_MODEL_STEP", "NotificationRoute.ROUTE_MODEL) {", "NotificationRoute.ROUTE_HISTORY) {"),
        Sample("ALL_PADDING_SIDES_APPLIED", "setPadding(padding.left, padding.top, padding.right, padding.bottom)", "setPadding(0, padding.top, 0, 0)"),
        Sample("ALL_PADDING_SIDES_APPLIED", "setPadding(padding.left, padding.top, padding.right, padding.bottom)", "setPadding(padding.left, padding.top, padding.left, padding.top)"),
        Sample("INSETS_WIRED_PER_SIDE", "Insets(bars.left, bars.top, bars.right, bars.bottom)", "Insets(bars.left, bars.top, bars.left, bars.top)"),
        Sample("INSETS_WIRED_PER_SIDE", "Insets(bars.left, bars.top, bars.right, bars.bottom)", "Insets(bars.left, bars.top, bars.right, bars.right)"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("activity", "column.addView(onboarding)", "column.addView(  onboarding  )"),
        Quiet("activity", "downloadSentence = null\n        downloadStatus.text = \"\"", "downloadSentence = null\n        downloadStatus.text = \"\" // cleared"),
        Quiet("activity", "applyInsets(settingsScroll)\n        registerBack()", "applyInsets(  settingsScroll  )\n        registerBack()"),
        Quiet("activity", "private var downloadSentence: String? = null", "private var downloadSentence: String? = null // the line"),
        Quiet("activity", "onDownloadModel = { app.tileHost.requestDownload() }", "onDownloadModel = {\n            app.tileHost.requestDownload()\n        }"),
        Quiet("activity", "ScrollView(this)", "ScrollView(  this  )"),
        Quiet("activity", "ACCESSIBILITY_SERVICE_COMPONENT,\n            modelReady = { modelReady.isReady() },", "ACCESSIBILITY_SERVICE_COMPONENT,\n            modelReady = { modelReady.isReady() }, // live"),
        Quiet("activity", "downloadLine = { downloadSentence },", "downloadLine = { downloadSentence }, // live"),
        Quiet("activity", "?.sizeMb?.let(", "?.sizeMb\n                ?.let("),
        Quiet("activity", "BreakerSwitchAdapter(app.armedSwitch),\n            ACCESSIBILITY_SERVICE_COMPONENT,", "BreakerSwitchAdapter(  app.armedSwitch  ),\n            ACCESSIBILITY_SERVICE_COMPONENT,"),
        Quiet("insets", "fun paddingFor(bars: Insets, cutout: Insets): Padding", "fun paddingFor(  bars: Insets, cutout: Insets  ): Padding"),
        Quiet("insets", "left = maxOf(bars.left, cutout.left)", "left = maxOf(bars.left, cutout.left) // max"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a launcher scroll gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }
    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))
    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code

    @Test
    fun `every rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a launcher scroll rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: launcher scroll rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: launcher scroll rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: launcher scroll rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
