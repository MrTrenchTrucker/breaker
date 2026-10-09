package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lines of `service/ModelNotifications.kt` and `host/OverlayTilePort.kt` that the other host
 * gates leave open, because no JVM test can run them: whether a denied permission shows nothing, which
 * channel and id each notice is built on, which request code, route and notification id it uses, when the
 * progress bar and the ongoing flag are set, what the cancelling calls take away, which sentence and icon
 * the done and failed notices carry, the launcher intent flags, and where the tile's theme comes from.
 * It works like `HostGateNotifyTest`: the rules read the real files with comments and literal text
 * removed by the shared scanner, each rule holds on the real file, is broken by at least two edited
 * samples, and stays quiet on harmless edits; an edit whose target text is missing fails by name.
 * The small icon of the other notices, the alert-once and auto-cancel flags are looks that only a device
 * shows, and are left to a look at the screen.
 */
internal class HostGateNoticeTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "notif" to dir + "service/ModelNotifications.kt",
        "port" to dir + "host/OverlayTilePort.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val file: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }
    /** The call `[head](...)` occurs once and its argument list names every one of [flags]. */
    private fun needs(name: String, file: String, head: String, vararg flags: String) = Rule(name, file) { code ->
        val calls = Regex("""\b$head\s*\(([^()]*)\)""").findAll(code).toList()
        calls.size == 1 && flags.all { Regex("""\b$it\b""").containsMatchIn(calls[0].groupValues[1]) }
    }
    /** The first group of [body] is found once and does not contain [banned]. */
    private fun lacks(name: String, file: String, body: String, banned: String) = Rule(name, file) { code ->
        val found = Regex("(?s)" + body).find(code)
        found != null && !Regex(banned).containsMatchIn(found.groupValues[1])
    }

    private val rules: List<Rule> = listOf(
        rx("SHOW_RETURNS_WHEN_NOTIFICATIONS_ARE_OFF", "notif", """\bif\s*\(\s*!\s*manager\s*\.\s*areNotificationsEnabled\s*\(\s*\)\s*\)\s*return\b"""),
        rx("CHANNEL_IS_MADE_FROM_ID_THEN_NAME_THEN_LOW_IMPORTANCE", "notif", """\bmanager\s*\.\s*createNotificationChannel\s*\(\s*NotificationChannel\s*\(\s*CHANNEL_ID\s*,\s*CHANNEL_NAME\s*,\s*NotificationManager\s*\.\s*IMPORTANCE_LOW\s*\)\s*\)"""),
        rx("NOTICE_IS_BUILT_ON_THE_CHANNEL_ID", "notif", """\bNotification\s*\.\s*Builder\s*\(\s*context\s*,\s*CHANNEL_ID\s*\)"""),
        rx("CONTENT_INTENT_GETS_THE_REQUEST_AND_THE_ROUTE_PASSED_IN", "notif", """\.\s*setContentIntent\s*\(\s*openLauncher\s*\(\s*request\s*,\s*route\s*\)\s*\)"""),
        rx("NOTIFY_USES_THE_ID_PASSED_IN", "notif", """\bmanager\s*\.\s*notify\s*\(\s*id\s*,\s*notification\s*\.\s*build\s*\(\s*\)\s*\)"""),
        rx("PROGRESS_AND_ONGOING_ONLY_WHEN_WORKING", "notif", """\bif\s*\(\s*working\s*\)\s*\{\s*notification\s*\.\s*setOngoing\s*\(\s*true\s*\)\s*\.\s*setCategory\s*\(\s*Notification\s*\.\s*CATEGORY_PROGRESS\s*\)\s*\.\s*setProgress\s*\(\s*0\s*,\s*0\s*,\s*true\s*\)\s*\}\s*else\b"""),
        rx("CANCEL_CANCELS_THE_ID_PASSED_IN", "notif", """\bgetSystemService\s*\(\s*NotificationManager::class\s*\.\s*java\s*\)\s*\.\s*cancel\s*\(\s*id\s*\)"""),
        rx("CLEAR_CANCELS_THE_MISSING_NOTICE_THEN_THE_TILE_NOTICE", "notif", """\boverride\s+fun\s+clear\s*\(\s*\)\s*\{\s*cancel\s*\(\s*MISSING_ID\s*\)\s*cancel\s*\(\s*UNAVAILABLE_ID\s*\)\s*\}"""),
        rx("DONE_CANCELS_THE_MISSING_NOTICE_AND_SHOWS_READY_WITH_THE_DONE_ICON", "notif", """\boverride\s+fun\s+done\s*\(\s*\)\s*\{\s*cancel\s*\(\s*MISSING_ID\s*\)\s*show\s*\(\s*DOWNLOAD_ID\s*,\s*DOWNLOAD_REQUEST\s*,\s*android\s*\.\s*R\s*\.\s*drawable\s*\.\s*stat_sys_download_done\s*,\s*MODEL_TITLE\s*,\s*MODEL_READY\s*,\s*null\s*,\s*false\s*\)\s*\}"""),
        rx("FAILED_SHOWS_THE_FAILURE_SENTENCE_WITH_THE_ERROR_ICON", "notif", """\boverride\s+fun\s+failed\s*\(\s*sentence\s*:\s*String\s*\)\s*\{\s*show\s*\(\s*DOWNLOAD_ID\s*,\s*DOWNLOAD_REQUEST\s*,\s*android\s*\.\s*R\s*\.\s*drawable\s*\.\s*stat_notify_error\s*,\s*MODEL_TITLE\s*,\s*sentence\s*,\s*null\s*,\s*false\s*\)\s*\}"""),
        rx("READY_SENTENCE_IS_ONLY_THE_DONE_NOTICE", "notif", """\bMODEL_READY\b""", 2),
        needs("LAUNCHER_INTENT_STARTS_A_NEW_TASK_AND_CLEARS_TOP", "notif", "addFlags", "FLAG_ACTIVITY_NEW_TASK", "FLAG_ACTIVITY_CLEAR_TOP"),
        needs("TAP_PENDING_INTENT_IS_IMMUTABLE_AND_UPDATES_CURRENT", "notif", "getActivity", "FLAG_IMMUTABLE", "FLAG_UPDATE_CURRENT"),
        rx("TILE_THEME_COMES_FROM_THE_CURRENT_THEME", "port", """\bval\s+theme\s*=\s*currentTheme\s*\(\s*\)"""),
        lacks("SHOW_NAMES_NO_FIXED_THEME", "port", """\boverride\s+fun\s+show\s*\(\s*\)\s*:\s*TileShow\s*\{(.*?)\boverride\s+fun\s+hide\b""", """\bTileTheme\b"""),
    )
    private val firing: List<Sample> = listOf(
        Sample("SHOW_RETURNS_WHEN_NOTIFICATIONS_ARE_OFF", "notif", "if (!manager.areNotificationsEnabled()) return", "if (manager.areNotificationsEnabled()) return"),
        Sample("SHOW_RETURNS_WHEN_NOTIFICATIONS_ARE_OFF", "notif", "if (!manager.areNotificationsEnabled()) return", "if (!manager.areNotificationsEnabled() == false) return"),
        Sample("CHANNEL_IS_MADE_FROM_ID_THEN_NAME_THEN_LOW_IMPORTANCE", "notif", "NotificationChannel(CHANNEL_ID, CHANNEL_NAME,", "NotificationChannel(CHANNEL_NAME, CHANNEL_ID,"),
        Sample("CHANNEL_IS_MADE_FROM_ID_THEN_NAME_THEN_LOW_IMPORTANCE", "notif", "NotificationChannel(CHANNEL_ID, CHANNEL_NAME,", "NotificationChannel(CHANNEL_ID, MODEL_TITLE,"),
        Sample("CHANNEL_IS_MADE_FROM_ID_THEN_NAME_THEN_LOW_IMPORTANCE", "notif", "manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))\n", ""),
        Sample("NOTICE_IS_BUILT_ON_THE_CHANNEL_ID", "notif", "Notification.Builder(context, CHANNEL_ID)", "Notification.Builder(context)"),
        Sample("NOTICE_IS_BUILT_ON_THE_CHANNEL_ID", "notif", "Notification.Builder(context, CHANNEL_ID)", "Notification.Builder(context, CHANNEL_NAME)"),
        Sample("CONTENT_INTENT_GETS_THE_REQUEST_AND_THE_ROUTE_PASSED_IN", "notif", "openLauncher(request, route)", "openLauncher(request, null)"),
        Sample("CONTENT_INTENT_GETS_THE_REQUEST_AND_THE_ROUTE_PASSED_IN", "notif", "openLauncher(request, route)", "openLauncher(MISSING_REQUEST, route)"),
        Sample("CONTENT_INTENT_GETS_THE_REQUEST_AND_THE_ROUTE_PASSED_IN", "notif", "openLauncher(request, route)", "openLauncher(id, route)"),
        Sample("NOTIFY_USES_THE_ID_PASSED_IN", "notif", "manager.notify(id, notification.build())", "manager.notify(MISSING_ID, notification.build())"),
        Sample("NOTIFY_USES_THE_ID_PASSED_IN", "notif", "manager.notify(id, notification.build())", "manager.notify(request, notification.build())"),
        Sample("PROGRESS_AND_ONGOING_ONLY_WHEN_WORKING", "notif", "if (working) {", "if (!working) {"),
        Sample("PROGRESS_AND_ONGOING_ONLY_WHEN_WORKING", "notif", "if (working) {", "if (true) {"),
        Sample("PROGRESS_AND_ONGOING_ONLY_WHEN_WORKING", "notif", "setOngoing(true)", "setOngoing(false)"),
        Sample("PROGRESS_AND_ONGOING_ONLY_WHEN_WORKING", "notif", ".setCategory(Notification.CATEGORY_PROGRESS)", ""),
        Sample("CANCEL_CANCELS_THE_ID_PASSED_IN", "notif", ".cancel(id)", ".cancel(MISSING_ID)"),
        Sample("CANCEL_CANCELS_THE_ID_PASSED_IN", "notif", ".cancel(id)", ".cancel(UNAVAILABLE_ID)"),
        Sample("CLEAR_CANCELS_THE_MISSING_NOTICE_THEN_THE_TILE_NOTICE", "notif", "cancel(MISSING_ID)\n        cancel(UNAVAILABLE_ID)", "cancel(UNAVAILABLE_ID)\n        cancel(MISSING_ID)"),
        Sample("CLEAR_CANCELS_THE_MISSING_NOTICE_THEN_THE_TILE_NOTICE", "notif", "cancel(MISSING_ID)\n        cancel(UNAVAILABLE_ID)", "cancel(MISSING_ID)\n        cancel(MISSING_ID)"),
        Sample("CLEAR_CANCELS_THE_MISSING_NOTICE_THEN_THE_TILE_NOTICE", "notif", "cancel(MISSING_ID)\n        cancel(UNAVAILABLE_ID)", "cancel(MISSING_ID)"),
        Sample("DONE_CANCELS_THE_MISSING_NOTICE_AND_SHOWS_READY_WITH_THE_DONE_ICON", "notif", "android.R.drawable.stat_sys_download_done", "android.R.drawable.stat_sys_download"),
        Sample("DONE_CANCELS_THE_MISSING_NOTICE_AND_SHOWS_READY_WITH_THE_DONE_ICON", "notif", "MODEL_TITLE, MODEL_READY, null, false", "MODEL_TITLE, TILE_UNAVAILABLE, null, false"),
        Sample("DONE_CANCELS_THE_MISSING_NOTICE_AND_SHOWS_READY_WITH_THE_DONE_ICON", "notif", "cancel(MISSING_ID)\n        show(DOWNLOAD_ID", "cancel(UNAVAILABLE_ID)\n        show(DOWNLOAD_ID"),
        Sample("FAILED_SHOWS_THE_FAILURE_SENTENCE_WITH_THE_ERROR_ICON", "notif", "MODEL_TITLE, sentence, null, false", "MODEL_TITLE, MODEL_READY, null, false"),
        Sample("FAILED_SHOWS_THE_FAILURE_SENTENCE_WITH_THE_ERROR_ICON", "notif", "android.R.drawable.stat_notify_error", "android.R.drawable.stat_sys_download_done"),
        Sample("FAILED_SHOWS_THE_FAILURE_SENTENCE_WITH_THE_ERROR_ICON", "notif", "MODEL_TITLE, sentence, null, false", "MODEL_TITLE, sentence, null, true"),
        Sample("READY_SENTENCE_IS_ONLY_THE_DONE_NOTICE", "notif", "MODEL_TITLE, sentence, null, false", "MODEL_TITLE, MODEL_READY, null, false"),
        Sample("READY_SENTENCE_IS_ONLY_THE_DONE_NOTICE", "notif", "TILE_TITLE, TILE_UNAVAILABLE, null, false", "TILE_TITLE, MODEL_READY, null, false"),
        Sample("LAUNCHER_INTENT_STARTS_A_NEW_TASK_AND_CLEARS_TOP", "notif", "Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP", "Intent.FLAG_ACTIVITY_NEW_TASK"),
        Sample("LAUNCHER_INTENT_STARTS_A_NEW_TASK_AND_CLEARS_TOP", "notif", "Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP", "Intent.FLAG_ACTIVITY_CLEAR_TOP"),
        Sample("LAUNCHER_INTENT_STARTS_A_NEW_TASK_AND_CLEARS_TOP", "notif", "Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP", "Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP"),
        Sample("TAP_PENDING_INTENT_IS_IMMUTABLE_AND_UPDATES_CURRENT", "notif", "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT", "PendingIntent.FLAG_IMMUTABLE"),
        Sample("TAP_PENDING_INTENT_IS_IMMUTABLE_AND_UPDATES_CURRENT", "notif", "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT", "PendingIntent.FLAG_UPDATE_CURRENT"),
        Sample("TAP_PENDING_INTENT_IS_IMMUTABLE_AND_UPDATES_CURRENT", "notif", "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT", "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT"),
        Sample("TILE_THEME_COMES_FROM_THE_CURRENT_THEME", "port", "val theme = currentTheme()", "val theme = TileTheme.LIGHT"),
        Sample("TILE_THEME_COMES_FROM_THE_CURRENT_THEME", "port", "val theme = currentTheme()", "val theme = TileTheme.DARK"),
        Sample("SHOW_NAMES_NO_FIXED_THEME", "port", "made.setTheme(theme)", "made.setTheme(TileTheme.LIGHT)"),
        Sample("SHOW_NAMES_NO_FIXED_THEME", "port", "val theme = currentTheme()", "val theme = TileTheme.DARK"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("notif", "if (!manager.areNotificationsEnabled()) return", "if ( !manager.areNotificationsEnabled() ) return // (manager.areNotificationsEnabled()) return"),
        Quiet("notif", "manager.notify(id, notification.build())", "manager.notify(id, notification.build()) // notify(MISSING_ID)"),
        Quiet("notif", "setOngoing(true)", "setOngoing(  true  )"),
        Quiet("notif", "const val MISSING_ID: Int = 2", "const val MISSING_ID: Int = 12"),
        Quiet("notif", "cancel(UNAVAILABLE_ID)\n    }", "cancel(UNAVAILABLE_ID) // cancel(MISSING_ID)\n    }"),
        Quiet("notif", "Intent.FLAG_ACTIVITY_CLEAR_TOP)", "Intent.FLAG_ACTIVITY_CLEAR_TOP /* or Intent.FLAG_ACTIVITY_SINGLE_TOP */)"),
        Quiet("notif", "PendingIntent.FLAG_IMMUTABLE or", "PendingIntent.FLAG_IMMUTABLE /* FLAG_MUTABLE */ or"),
        Quiet("notif", "MODEL_TITLE, sentence, null, false)", "MODEL_TITLE, sentence, null, false) // MODEL_READY"),
        Quiet("port", "val theme = currentTheme()", "val theme = currentTheme() // TileTheme.LIGHT"),
        Quiet("port", "made.setTheme(theme)", "made.setTheme(theme) // TileTheme.DARK"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a host gate sample lost its text '$old'" }
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
        assertEquals("app: a notice rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: notice rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
        for (key in files.keys) assertTrue("app: no harmless edit is listed for $key", quiet.any { it.file == key })
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertEquals("app: sample for ${sample.rule} names the wrong file", rule.file, sample.file)
            assertFalse("app: notice rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: notice rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
