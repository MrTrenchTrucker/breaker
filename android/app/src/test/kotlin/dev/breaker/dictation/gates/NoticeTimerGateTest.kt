package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the four-second notice clear that no JVM test can run against the Android handler: the one
 * main-looper queue that runs it, the tile host that wires the notice port over the tile, the tap
 * report that cancels the timer, and the order in which the port cancels before each tile change.
 * The rules read the real files with comments and literal text removed by the shared scanner. Each
 * rule holds on the real file, is broken by at least two edited samples, and stays quiet on a
 * comment-only edit; an edit whose target text is missing fails by name.
 */
internal class NoticeTimerGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "threads" to dir + "host/AppThreads.kt",
        "host" to dir + "host/TileHost.kt",
        "port" to dir + "wiring/NoticeClearingTilePort.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        rx("MAIN_LOOPER_POST_IS_ALSO_A_DELAYED_POST", "threads", """\bclass\s+MainLooperPost\s*:\s*MainPost\s*,\s*DelayedPost\s*\{"""),
        rx(
            "DELAYED_POST_QUEUES_ON_THE_ONE_HANDLER",
            "threads",
            """override\s+fun\s+postDelayed\s*\(\s*delayMs\s*:\s*Long\s*,\s*block\s*:\s*\(\)\s*->\s*Unit\s*\)\s*:\s*PostHandle\s*\{\s*val\s+task\s*=\s*Runnable\s*\{\s*block\s*\(\s*\)\s*\}\s*handler\s*\.\s*postDelayed\s*\(\s*task\s*,\s*delayMs\s*\)\s*return\s+object\s*:\s*PostHandle\s*\{\s*override\s+fun\s+cancel\s*\(\s*\)\s*\{\s*handler\s*\.\s*removeCallbacks\s*\(\s*task\s*\)\s*\}\s*\}\s*\}""",
        ),
        rx("THE_APP_NAMES_ONE_MAIN_HANDLER", "threads", """\bHandler\s*\(\s*Looper\s*\.\s*getMainLooper\s*\(\s*\)\s*\)"""),
        rx(
            "TILE_HOST_BUILDS_THE_NOTICE_PORT_OVER_THE_TILE",
            "host",
            """private\s+val\s+noticePort\s*=\s*NoticeClearingTilePort\s*\(\s*tile\s*,\s*main\s*,\s*onDismissed\s*=\s*\{\s*setNotificationLine\s*\(\s*null\s*\)\s*\}\s*,?\s*\)""",
        ),
        Rule("NOTICE_PORT_IS_DECLARED_BEFORE_THE_INIT_BLOCK", "host") { code ->
            val at: Int = code.indexOf("private val noticePort")
            at >= 0 && code.indexOf("init {") > at
        },
        rx(
            "TAP_REPORT_REACHES_THE_NOTICE_PORT_ON_MAIN",
            "host",
            """onNoticeDismissed\s*=\s*\{\s*main\s*\.\s*post\s*\{\s*noticePort\s*\.\s*onNoticeDismissed\s*\(\s*\)\s*\}\s*\}""",
        ),
        rx("COORDINATOR_GETS_THE_NOTICE_PORT", "host", """TileCoordinator\s*\(\s*noticePort\s*,"""),
        rx("COORDINATOR_NEVER_GETS_THE_RAW_TILE", "host", """TileCoordinator\s*\(\s*tile\s*,""", times = 0),
        rx("LEVEL_FEED_GETS_THE_NOTICE_PORT", "host", """LevelFeed\s*\(\s*noticePort\s*,\s*main\s*\)"""),
        rx("NOTICE_CLEAR_IS_FOUR_THOUSAND_MILLISECONDS", "port", """internal\s+const\s+val\s+NOTICE_CLEAR_MS\s*:\s*Long\s*=\s*4000L"""),
        rx("SCHEDULE_POSTS_THE_CLEAR_AFTER_THE_DELAY", "port", """pending\s*=\s*delayed\s*\.\s*postDelayed\s*\(\s*NOTICE_CLEAR_MS\s*\)\s*\{\s*clearAfterDelay\s*\(\s*\)\s*\}"""),
        rx("THE_TIMER_CLEARS_THE_TILE_NOT_A_SHOW", "port", """private\s+fun\s+clearAfterDelay\s*\(\s*\)\s*\{\s*pending\s*=\s*null\s*try\s*\{\s*inner\s*\.\s*clearNotice\s*\(\s*\)\s*\}"""),
        rx("INNER_IS_CLEARED_IN_TWO_PLACES_ONLY", "port", """\binner\s*\.\s*clearNotice\s*\(\s*\)""", times = 2),
        rx("SHOW_CANCELS_FIRST", "port", """override\s+fun\s+show\s*\(\s*\)\s*:\s*TileShow\s*\{\s*cancelPending\s*\(\s*\)\s*return\s+inner\s*\.\s*show\s*\(\s*\)\s*\}"""),
        rx("HIDE_CANCELS_FIRST", "port", """override\s+fun\s+hide\s*\(\s*\)\s*\{\s*cancelPending\s*\(\s*\)\s*inner\s*\.\s*hide\s*\(\s*\)\s*\}"""),
        rx("SET_STATE_CANCELS_FIRST", "port", """override\s+fun\s+setState\s*\(\s*state\s*:\s*TileState\s*\)\s*\{\s*cancelPending\s*\(\s*\)\s*inner\s*\.\s*setState\s*\(\s*state\s*\)\s*\}"""),
        rx("LEVEL_NEVER_CANCELS", "port", """override\s+fun\s+level\s*\(\s*level\s*:\s*Float\s*\)\s*\{\s*inner\s*\.\s*level\s*\(\s*level\s*\)\s*\}"""),
        rx("SHOW_NOTICE_CANCELS_SHOWS_THEN_SCHEDULES", "port", """override\s+fun\s+showNotice\s*\(\s*text\s*:\s*String\s*\)\s*\{\s*cancelPending\s*\(\s*\)\s*inner\s*\.\s*showNotice\s*\(\s*text\s*\)\s*schedule\s*\(\s*\)\s*\}"""),
        rx("CLEAR_NOTICE_CANCELS_FIRST", "port", """override\s+fun\s+clearNotice\s*\(\s*\)\s*\{\s*cancelPending\s*\(\s*\)\s*inner\s*\.\s*clearNotice\s*\(\s*\)\s*\}"""),
        rx("TAP_REPORT_CANCELS_AND_NEVER_CLEARS", "port", """fun\s+onNoticeDismissed\s*\(\s*\)\s*\{\s*cancelPending\s*\(\s*\)\s*try\s*\{\s*onDismissed\s*\(\s*\)\s*\}"""),
    )

    private val firing: List<Sample> = listOf(
        Sample("MAIN_LOOPER_POST_IS_ALSO_A_DELAYED_POST", "MainPost, DelayedPost {", "MainPost {"),
        Sample("MAIN_LOOPER_POST_IS_ALSO_A_DELAYED_POST", "MainPost, DelayedPost {", "MainPost, Other {"),
        Sample("DELAYED_POST_QUEUES_ON_THE_ONE_HANDLER", "handler.postDelayed(task, delayMs)", "handler.post(task)"),
        Sample("DELAYED_POST_QUEUES_ON_THE_ONE_HANDLER", "handler.removeCallbacks(task)", "Unit"),
        Sample("THE_APP_NAMES_ONE_MAIN_HANDLER", "private val handler = Handler(Looper.getMainLooper())", "private val handler = Handler(Looper.getMainLooper())\n    private val other = Handler(Looper.getMainLooper())"),
        Sample("THE_APP_NAMES_ONE_MAIN_HANDLER", "private val handler = Handler(Looper.getMainLooper())", "private val handler = Handler(Looper.myLooper()!!)"),
        Sample("TILE_HOST_BUILDS_THE_NOTICE_PORT_OVER_THE_TILE", "NoticeClearingTilePort(tile, main, onDismissed", "NoticeClearingTilePort(tile, null, onDismissed"),
        Sample("TILE_HOST_BUILDS_THE_NOTICE_PORT_OVER_THE_TILE", "NoticeClearingTilePort(tile, main, onDismissed = { setNotificationLine(null) })", "NoticeClearingTilePort(tile, main)"),
        Sample("NOTICE_PORT_IS_DECLARED_BEFORE_THE_INIT_BLOCK", "    private val noticePort = NoticeClearingTilePort(tile, main, onDismissed = { setNotificationLine(null) })\n", ""),
        Sample("NOTICE_PORT_IS_DECLARED_BEFORE_THE_INIT_BLOCK", "private val noticePort = ", "private val noticeXPort = "),
        Sample("TAP_REPORT_REACHES_THE_NOTICE_PORT_ON_MAIN", "onNoticeDismissed = { main.post { noticePort.onNoticeDismissed() } }", "onNoticeDismissed = { }"),
        Sample("TAP_REPORT_REACHES_THE_NOTICE_PORT_ON_MAIN", "onNoticeDismissed = { main.post { noticePort.onNoticeDismissed() } }", "onNoticeDismissed = { noticePort.onNoticeDismissed() }"),
        Sample("COORDINATOR_GETS_THE_NOTICE_PORT", "            noticePort,\n            main,", "            tile,\n            main,"),
        Sample("COORDINATOR_GETS_THE_NOTICE_PORT", "TileCoordinator(\n            noticePort,", "TileCoordinator(\n            main,"),
        Sample("COORDINATOR_NEVER_GETS_THE_RAW_TILE", "            noticePort,\n            main,", "            tile,\n            main,"),
        Sample("COORDINATOR_NEVER_GETS_THE_RAW_TILE", "private val coordinator: TileCoordinator by lazy {", "private val coordinator: TileCoordinator by lazy {\n        TileCoordinator(tile, main)"),
        Sample("LEVEL_FEED_GETS_THE_NOTICE_PORT", "LevelFeed(noticePort, main)", "LevelFeed(tile, main)"),
        Sample("LEVEL_FEED_GETS_THE_NOTICE_PORT", "LevelFeed(noticePort, main)", "LevelFeed(noticePort, null)"),
        Sample("NOTICE_CLEAR_IS_FOUR_THOUSAND_MILLISECONDS", "NOTICE_CLEAR_MS: Long = 4000L", "NOTICE_CLEAR_MS: Long = 3000L"),
        Sample("NOTICE_CLEAR_IS_FOUR_THOUSAND_MILLISECONDS", "NOTICE_CLEAR_MS: Long = 4000L", "NOTICE_CLEAR_MS: Long = 4001L"),
        Sample("SCHEDULE_POSTS_THE_CLEAR_AFTER_THE_DELAY", "pending = delayed.postDelayed(NOTICE_CLEAR_MS) { clearAfterDelay() }", "pending = delayed.postDelayed(NOTICE_CLEAR_MS) { inner.clearNotice() }"),
        Sample("SCHEDULE_POSTS_THE_CLEAR_AFTER_THE_DELAY", "pending = delayed.postDelayed(NOTICE_CLEAR_MS) { clearAfterDelay() }", "pending = delayed.postDelayed(NOTICE_CLEAR_MS) { }"),
        Sample("THE_TIMER_CLEARS_THE_TILE_NOT_A_SHOW", "        pending = null\n        try {\n            inner.clearNotice()", "        pending = null\n        try {\n            inner.showNotice(\"\")"),
        Sample("THE_TIMER_CLEARS_THE_TILE_NOT_A_SHOW", "        pending = null\n        try {\n            inner.clearNotice()", "        pending = null\n        try {\n            inner.clearNotice()\n            inner.clearNotice()"),
        Sample("INNER_IS_CLEARED_IN_TWO_PLACES_ONLY", "    override fun clearNotice() {\n        cancelPending()\n        inner.clearNotice()", "    override fun clearNotice() {\n        cancelPending()\n        inner.clearNotice()\n        inner.clearNotice()"),
        Sample("INNER_IS_CLEARED_IN_TWO_PLACES_ONLY", "    override fun clearNotice() {\n        cancelPending()\n        inner.clearNotice()", "    override fun clearNotice() {\n        cancelPending()"),
        Sample("SHOW_CANCELS_FIRST", "        cancelPending()\n        return inner.show()", "        return inner.show()\n        cancelPending()"),
        Sample("SHOW_CANCELS_FIRST", "return inner.show()", "return inner.hide()"),
        Sample("HIDE_CANCELS_FIRST", "        cancelPending()\n        inner.hide()", "        inner.hide()\n        cancelPending()"),
        Sample("HIDE_CANCELS_FIRST", "        inner.hide()\n    }", "        inner.show()\n    }"),
        Sample("SET_STATE_CANCELS_FIRST", "        cancelPending()\n        inner.setState(state)", "        inner.setState(state)\n        cancelPending()"),
        Sample("SET_STATE_CANCELS_FIRST", "        inner.setState(state)\n    }", "        inner.setState(state.also { })\n    }"),
        Sample("LEVEL_NEVER_CANCELS", "        inner.level(level)\n    }", "        cancelPending()\n        inner.level(level)\n    }"),
        Sample("LEVEL_NEVER_CANCELS", "override fun level(level: Float) {", "override fun level(level: Float) {\n        cancelPending()"),
        Sample("SHOW_NOTICE_CANCELS_SHOWS_THEN_SCHEDULES", "        cancelPending()\n        inner.showNotice(text)\n        schedule()", "        inner.showNotice(text)\n        cancelPending()\n        schedule()"),
        Sample("SHOW_NOTICE_CANCELS_SHOWS_THEN_SCHEDULES", "        inner.showNotice(text)\n        schedule()", "        inner.showNotice(text)"),
        Sample("CLEAR_NOTICE_CANCELS_FIRST", "        cancelPending()\n        inner.clearNotice()\n    }", "        inner.clearNotice()\n        cancelPending()\n    }"),
        Sample("CLEAR_NOTICE_CANCELS_FIRST", "    override fun clearNotice() {\n        cancelPending()", "    override fun clearNotice() {"),
        Sample("TAP_REPORT_CANCELS_AND_NEVER_CLEARS", "    fun onNoticeDismissed() {\n        cancelPending()", "    fun onNoticeDismissed() {"),
        Sample("TAP_REPORT_CANCELS_AND_NEVER_CLEARS", "            onDismissed()\n        } catch (e: Exception) {", "            onDismissed()\n            inner.clearNotice()\n        } catch (e: Exception) {"),
    )

    /** Edits that change only a comment; no rule may fire on them. */
    private val quiet: List<Quiet> = listOf(
        Quiet("port", "    fun onNoticeDismissed() {", "    // fun onNoticeDismissed() { cancelPending() }\n    fun onNoticeDismissed() {"),
        Quiet("threads", "    private val handler = Handler(Looper.getMainLooper())", "    /* Handler(Looper.getMainLooper()) */\n    private val handler = Handler(Looper.getMainLooper())"),
        Quiet("host", "    private val coordinator: TileCoordinator by lazy {", "    // TileCoordinator(tile, main)\n    private val coordinator: TileCoordinator by lazy {"),
    )

    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))

    /** The comment-stripped code of [key], with [old] replaced by [new] when given; fails by name when the text is absent. */
    private fun codeOf(key: String, old: String? = null, new: String = ""): String {
        val text: String = raw(key)
        if (old == null) return AppSourceFiles.strip(text).code
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a notice timer sample lost its text '$old' in ${files.getValue(key)}" }
        return AppSourceFiles.strip(text.substring(0, at) + new + text.substring(at + old.length)).code
    }

    @Test
    fun `every rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a notice timer rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue("app: notice timer rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            val rule: Rule = byName.getValue(sample.rule)
            assertFalse(
                "app: notice timer rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(rule.file, sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `no rule fires on a comment-only edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue(
                    "app: notice timer rule ${rule.name} must stay quiet on the comment-only edit '${sample.old}' => '${sample.new}'",
                    rule.holds(codeOf(sample.file, sample.old, sample.new)),
                )
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            codeOf("host", "no such text in the host", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text in the host"))
    }
}
