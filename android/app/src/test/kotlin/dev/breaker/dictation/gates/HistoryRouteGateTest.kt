package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the History screen of the launcher activity and the route check in `service/NotificationRoute.kt`,
 * because no JVM test can run them. The screen opens from a notification tap only on a fresh create, one
 * function opens it and one function closes it, and Back returns to the settings column on both paths:
 * the platform callback on API 33 and up, and `onBackPressed` before that. The rules read the real files
 * with comments and literal text removed by the shared scanner; each rule holds on the real file, is broken
 * by at least two edited samples, and stays quiet on harmless edits.
 */
internal class HistoryRouteGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "activity" to dir + "SettingsLauncherActivity.kt",
        "route" to dir + "service/NotificationRoute.kt",
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
        once2("HISTORY_VIEW_IS_BUILT_ONCE_FROM_THE_APPS_STORE", "activity", """\bcreateHistoryView\b""", """\bcreateHistoryView\s*\(\s*this\s*,\s*app\s*\.\s*historyStore\s*\)"""),
        rx("NO_OTHER_HISTORY_STORE", "activity", """\b(SqliteHistoryStore|LazyHistoryStore|HistoryStore)\b""", 0),
        rx("THE_BUTTON_OPENS_HISTORY", "activity", """\bhistory\s*\.\s*setOnClickListener\s*\{\s*openHistory\s*\(\s*\)\s*\}"""),
        rx("THE_ROUTE_OPENS_HISTORY_ONLY_ON_A_FRESH_CREATE", "activity", """\bif\s*\(\s*savedInstanceState\s*==\s*null\s*&&\s*NotificationRoute\s*\.\s*opensHistory\s*\(\s*intent\s*\?\s*\.\s*getStringExtra\s*\(\s*NotificationRoute\s*\.\s*EXTRA_ROUTE\s*\)\s*\)\s*\)\s*\{\s*openHistory\s*\(\s*\)\s*\}"""),
        rx("OPEN_HISTORY_HAS_ONE_BODY_AND_TWO_CALLERS", "activity", """\bopenHistory\s*\(\s*\)""", 3),
        rx("NO_ON_NEW_INTENT", "activity", """\bonNewIntent\b""", 0),
        rx("OPEN_SHOWS_THE_HISTORY_VIEW_AND_REGISTERS_BACK", "activity", """\bprivate\s+fun\s+openHistory\s*\(\s*\)\s*\{\s*if\s*\(\s*historyShown\s*\)\s*return\s+historyShown\s*=\s*true\s+val\s+app\s*=\s*applicationContext\s+as\s+BreakerApp\s+setContentView\s*\(\s*dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*ui\s*\.\s*createHistoryView\s*\(\s*this\s*,\s*app\s*\.\s*historyStore\s*\)\s*\)\s+registerBack\s*\(\s*\)\s*\}"""),
        rx("CLOSE_RETURNS_TO_THE_SETTINGS_COLUMN", "activity", """\bprivate\s+fun\s+closeHistory\s*\(\s*\)\s*\{\s*if\s*\(\s*!historyShown\s*\)\s*return\s+historyShown\s*=\s*false\s+unregisterBack\s*\(\s*\)\s+setContentView\s*\(\s*settingsColumn\s*\)\s*\}"""),
        rx("BACK_ON_33_AND_UP_REGISTERS_A_PLATFORM_CALLBACK_THAT_CLOSES_HISTORY", "activity", """\bprivate\s+fun\s+registerBack\s*\(\s*\)\s*\{\s*if\s*\(\s*android\s*\.\s*os\s*\.\s*Build\s*\.\s*VERSION\s*\.\s*SDK_INT\s*>=\s*33\s*\)\s*\{\s*val\s+callback\s*=\s*android\s*\.\s*window\s*\.\s*OnBackInvokedCallback\s*\{\s*closeHistory\s*\(\s*\)\s*\}\s*backCallback\s*=\s*callback\s*onBackInvokedDispatcher\s*\.\s*registerOnBackInvokedCallback\s*\(\s*android\s*\.\s*window\s*\.\s*OnBackInvokedDispatcher\s*\.\s*PRIORITY_DEFAULT\s*,\s*callback\s*,?\s*\)\s*\}\s*\}"""),
        rx("BACK_ON_33_AND_UP_UNREGISTERS_THE_CALLBACK", "activity", """\bprivate\s+fun\s+unregisterBack\s*\(\s*\)\s*\{\s*if\s*\(\s*android\s*\.\s*os\s*\.\s*Build\s*\.\s*VERSION\s*\.\s*SDK_INT\s*>=\s*33\s*\)\s*\{\s*backCallback\s*\?\s*\.\s*let\s*\{\s*onBackInvokedDispatcher\s*\.\s*unregisterOnBackInvokedCallback\s*\(\s*it\s*\)\s*\}\s*backCallback\s*=\s*null\s*\}\s*\}"""),
        rx("BACK_PRESSED_CLOSES_HISTORY_ON_EVERY_SDK_LEVEL", "activity", """\boverride\s+fun\s+onBackPressed\s*\(\s*\)\s*\{\s*if\s*\(\s*historyShown\s*\)\s*closeHistory\s*\(\s*\)\s*else\s+super\s*\.\s*onBackPressed\s*\(\s*\)\s*\}"""),
        rx("BOTH_BACK_PATHS_CLOSE_THROUGH_ONE_FUNCTION", "activity", """\bcloseHistory\s*\(\s*\)""", 3),
        rx("REGISTER_BACK_IS_CALLED_ONLY_FROM_OPEN_HISTORY", "activity", """\bregisterBack\s*\(\s*\)""", 2),
        once2("THE_BACK_SUPPRESSION_IS_ON_ONBACKPRESSED_ONLY", "activity", """@SuppressLint\s*\(\s*""\s*\)\s+override\s+fun\s+onBackPressed""", """@\s*SuppressLint\b"""),
        rx("THE_ROUTE_FUNCTION_IS_AN_EXACT_MATCH", "route", """\bfun\s+opensHistory\s*\(\s*route\s*:\s*String\?\s*\)\s*:\s*Boolean\s*=\s*route\s*==\s*ROUTE_HISTORY\b"""),
    )

    private val firing: List<Sample> = listOf(
        Sample("HISTORY_VIEW_IS_BUILT_ONCE_FROM_THE_APPS_STORE", "app.historyStore", "SqliteHistoryStore.create(this, SystemClockAdapter)"),
        Sample("HISTORY_VIEW_IS_BUILT_ONCE_FROM_THE_APPS_STORE", "setContentView(settingsColumn)", "setContentView(settingsColumn)\n        createHistoryView(this, app.historyStore)"),
        Sample("NO_OTHER_HISTORY_STORE", "private var historyShown: Boolean = false", "private var historyShown: Boolean = false\n    private var spare: HistoryStore? = null"),
        Sample("NO_OTHER_HISTORY_STORE", "private lateinit var settingsColumn: LinearLayout", "private lateinit var settingsColumn: LinearLayout\n    private val spare: LazyHistoryStore? = null"),
        Sample("THE_BUTTON_OPENS_HISTORY", "history.setOnClickListener { openHistory() }", "history.setOnClickListener { }"),
        Sample("THE_BUTTON_OPENS_HISTORY", "history.setOnClickListener { openHistory() }", "history.setOnClickListener { closeHistory() }"),
        Sample("THE_ROUTE_OPENS_HISTORY_ONLY_ON_A_FRESH_CREATE", "if (savedInstanceState == null && NotificationRoute", "if (NotificationRoute"),
        Sample("THE_ROUTE_OPENS_HISTORY_ONLY_ON_A_FRESH_CREATE", "NotificationRoute.opensHistory(intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE))", "intent?.getStringExtra(NotificationRoute.EXTRA_ROUTE) == NotificationRoute.ROUTE_MODEL"),
        Sample("OPEN_HISTORY_HAS_ONE_BODY_AND_TWO_CALLERS", "private fun closeHistory() {", "private fun closeHistory() {\n        openHistory()"),
        Sample("OPEN_HISTORY_HAS_ONE_BODY_AND_TWO_CALLERS", "setContentView(settingsColumn)", "setContentView(settingsColumn)\n        openHistory()"),
        Sample("NO_ON_NEW_INTENT", "override fun onStart() {", "override fun onNewIntent(intent: android.content.Intent) {}\n\n    override fun onStart() {"),
        Sample("NO_ON_NEW_INTENT", "override fun onResume() {", "override fun onNewIntent(intent: android.content.Intent) {\n    }\n\n    override fun onResume() {"),
        Sample("OPEN_SHOWS_THE_HISTORY_VIEW_AND_REGISTERS_BACK", "        registerBack()\n    }", "    }"),
        Sample("OPEN_SHOWS_THE_HISTORY_VIEW_AND_REGISTERS_BACK", "historyShown = true", "historyShown = false"),
        Sample("CLOSE_RETURNS_TO_THE_SETTINGS_COLUMN", "unregisterBack()\n        setContentView(settingsColumn)", "setContentView(settingsColumn)"),
        Sample("CLOSE_RETURNS_TO_THE_SETTINGS_COLUMN", "historyShown = false", "historyShown = true"),
        Sample("BACK_ON_33_AND_UP_REGISTERS_A_PLATFORM_CALLBACK_THAT_CLOSES_HISTORY", "            onBackInvokedDispatcher.registerOnBackInvokedCallback(\n                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,\n                callback,\n            )\n", ""),
        Sample("BACK_ON_33_AND_UP_REGISTERS_A_PLATFORM_CALLBACK_THAT_CLOSES_HISTORY", "VERSION.SDK_INT >= 33) {\n            val callback", "VERSION.SDK_INT >= 34) {\n            val callback"),
        Sample("BACK_ON_33_AND_UP_UNREGISTERS_THE_CALLBACK", "backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }", ""),
        Sample("BACK_ON_33_AND_UP_UNREGISTERS_THE_CALLBACK", "VERSION.SDK_INT >= 33) {\n            backCallback", "VERSION.SDK_INT >= 34) {\n            backCallback"),
        Sample("BACK_PRESSED_CLOSES_HISTORY_ON_EVERY_SDK_LEVEL", " else super.onBackPressed()", ""),
        Sample("BACK_PRESSED_CLOSES_HISTORY_ON_EVERY_SDK_LEVEL", "if (historyShown) closeHistory() else", "if (historyShown) Unit else"),
        Sample("BACK_PRESSED_CLOSES_HISTORY_ON_EVERY_SDK_LEVEL", "        if (historyShown) closeHistory() else super.onBackPressed()", "        if (android.os.Build.VERSION.SDK_INT < 33 && historyShown) closeHistory() else super.onBackPressed()"),
        Sample("BOTH_BACK_PATHS_CLOSE_THROUGH_ONE_FUNCTION", "OnBackInvokedCallback { closeHistory() }", "OnBackInvokedCallback { historyShown = false }"),
        Sample("BOTH_BACK_PATHS_CLOSE_THROUGH_ONE_FUNCTION", "if (historyShown) closeHistory() else", "if (historyShown) historyShown = false else"),
        Sample("REGISTER_BACK_IS_CALLED_ONLY_FROM_OPEN_HISTORY", "        settingsColumn = column", "        settingsColumn = column\n        registerBack()"),
        Sample("REGISTER_BACK_IS_CALLED_ONLY_FROM_OPEN_HISTORY", "        unregisterBack()\n        setContentView(settingsColumn)", "        unregisterBack()\n        registerBack()\n        setContentView(settingsColumn)"),
        Sample("THE_ROUTE_FUNCTION_IS_AN_EXACT_MATCH", "route == ROUTE_HISTORY", "route == ROUTE_MODEL"),
        Sample("THE_ROUTE_FUNCTION_IS_AN_EXACT_MATCH", "fun opensHistory(route: String?)", "fun opensHistory(route: String)"),
        Sample("THE_BACK_SUPPRESSION_IS_ON_ONBACKPRESSED_ONLY", "    @SuppressLint(\"GestureBackNavigation\")\n    override fun onBackPressed() {", "    override fun onBackPressed() {"),
        Sample("THE_BACK_SUPPRESSION_IS_ON_ONBACKPRESSED_ONLY", "    private fun openHistory() {", "    @SuppressLint(\"GestureBackNavigation\")\n    private fun openHistory() {"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("activity", "history.setOnClickListener { openHistory() }", "history.setOnClickListener {\n            openHistory()\n        }"),
        Quiet("activity", "private fun closeHistory() {", "// openHistory() closeHistory() onNewIntent createHistoryView(this, app.historyStore)\n    private fun closeHistory() {"),
        Quiet("activity", "HISTORY_LABEL: String = \"History\"", "HISTORY_LABEL: String = \"Open the history\""),
        Quiet("activity", "if (historyShown) closeHistory() else super.onBackPressed()", "if (historyShown)\n            closeHistory()\n        else\n            super.onBackPressed()"),
        Quiet("route", "/** The route that shows the history of transcriptions. */", "/** The route that shows the history of transcriptions; the check is exact. */"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a history gate sample lost its text '$old'" }
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
        assertEquals("app: a history rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: history rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: history rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: history rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }

    @Test
    fun `the suppression names the one lint check`() {
        val suppression = Regex("""@SuppressLint\(\s*"GestureBackNavigation"\s*\)\s+override\s+fun\s+onBackPressed""")
        val text: String = raw("activity")
        assertEquals("app: the activity must carry exactly one GestureBackNavigation suppression on onBackPressed", 1, suppression.findAll(text).count())
        assertEquals("app: the suppression must stop matching when the lint check is renamed", 0, suppression.findAll(edit(text, "GestureBackNavigation", "OtherCheck")).count())
        assertEquals("app: the suppression must stop matching when its annotation line is removed", 0, suppression.findAll(edit(text, "    @SuppressLint(\"GestureBackNavigation\")\n", "")).count())
    }
}
