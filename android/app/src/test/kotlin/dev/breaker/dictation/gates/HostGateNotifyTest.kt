package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the model notifications and the launcher activity's button, because no JVM test can run them:
 * `service/ModelNotifications.kt` and `SettingsLauncherActivity.kt`. It is the other half of
 * `HostGateTest` and works the same way: the rules read the real files with comments and literal text
 * removed by the shared scanner, each rule holds on the real file, is broken by at least two edited
 * samples, and stays quiet on harmless edits; an edit whose target text is missing fails by name, so a
 * sample cannot go quiet by a typo.
 */
internal class HostGateNotifyTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "notif" to dir + "service/ModelNotifications.kt",
        "activity" to dir + "SettingsLauncherActivity.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }
    /** The constants named `..._[suffix]: Int = n` are at least three, all different and all 2 or more. */
    private fun codes(name: String, suffix: String) = Rule(name, "notif") { code ->
        val values = Regex("""\bconst\s+val\s+\w+_$suffix\s*:\s*Int\s*=\s*(\d+)""").findAll(code).map { it.groupValues[1].toInt() }.toList()
        values.size >= 3 && values.toSet().size == values.size && values.all { it >= 2 }
    }

    private val rules: List<Rule> = listOf(
        rx("NOTICE_TAP_IS_AN_IMMUTABLE_ACTIVITY_INTENT", "notif", """\bPendingIntent\s*\.\s*getActivity\s*\(\s*context\s*,\s*request\s*,\s*intent\s*,\s*PendingIntent\s*\.\s*FLAG_IMMUTABLE\b"""),
        rx("ONE_ACTIVITY_PENDING_INTENT", "notif", """\bgetActivity\s*\("""),
        rx("NO_MUTABLE_FLAG", "notif", """\bFLAG_MUTABLE\b""", 0),
        rx("ROUTE_IS_THE_ONLY_EXTRA", "notif", """\bputExtra\s*\("""),
        rx("ROUTE_EXTRA_IS_THE_ROUTE_NAME", "notif", """\bputExtra\s*\(\s*NotificationRoute\s*\.\s*EXTRA_ROUTE\s*,\s*route\s*\)"""),
        rx("MISSING_NOTICE_SAYS_THE_FIXED_SENTENCE_AND_OPENS_THE_MODEL_ROUTE", "notif", """\boverride\s+fun\s+showMissing\s*\(\s*\)\s*\{\s*show\s*\([^)]*\bModelSentences\s*\.\s*NO_MODEL\s*,\s*NotificationRoute\s*\.\s*ROUTE_MODEL\s*,\s*false\s*\)\s*\}"""),
        rx("TILE_NOTICE_OPENS_THE_LAUNCHER_WITHOUT_A_ROUTE", "notif", """\boverride\s+fun\s+showTileUnavailable\s*\(\s*\)\s*\{\s*show\s*\([^)]*\bTILE_UNAVAILABLE\s*,\s*null\s*,\s*false\s*\)\s*\}"""),
        rx("MODEL_ROUTE_IS_USED_ONCE", "notif", """\bROUTE_MODEL\b"""),
        rx("CHANNEL_IS_QUIET", "notif", """\bNotificationManager\s*\.\s*IMPORTANCE_LOW\b"""),
        rx("CHANNEL_HAS_NO_OTHER_IMPORTANCE", "notif", """\bIMPORTANCE_(?!LOW\b)\w+""", 0),
        rx("DENIED_NOTIFICATIONS_SHOW_NOTHING", "notif", """\bareNotificationsEnabled\s*\(\s*\)\s*\)\s*return\b"""),
        rx("PLATFORM_REFUSAL_IS_CAUGHT", "notif", """\bcatch\s*\(\s*\w+\s*:\s*RuntimeException\s*\)""", 2),
        rx("DONE_TAKES_THE_MISSING_NOTICE_AWAY", "notif", """\boverride\s+fun\s+done\s*\(\s*\)\s*\{\s*cancel\s*\(\s*MISSING_ID\s*\)\s*show\s*\("""),
        rx("PROGRESS_IS_INDETERMINATE", "notif", """\bsetProgress\s*\(\s*0\s*,\s*0\s*,\s*true\s*\)"""),
        rx("ONGOING_DICTATION_NOTIFICATION_AND_RESOURCES_ARE_LEFT_ALONE", "notif", """\bDictationNotification\b|\bNOTIFICATION_ID\b|\bstartForeground\b|\bR\s*\.\s*string\b|\bgetString\b""", 0),
        codes("REQUEST_CODES_DIFFER_AND_ARE_NOT_0_OR_1", "REQUEST"),
        codes("NOTIFICATION_IDS_DIFFER_AND_ARE_NOT_1", "ID"),
        rx("DOWNLOADING_NOTICE_IS_ONGOING", "notif", """\boverride\s+fun\s+downloading\s*\(\s*\)\s*\{\s*show\s*\([^)]*,\s*true\s*\)\s*\}"""),
        rx("DONE_NOTICE_IS_NOT_ONGOING", "notif", """\boverride\s+fun\s+done\s*\(\s*\)\s*\{\s*cancel\s*\(\s*MISSING_ID\s*\)\s*show\s*\([^)]*,\s*false\s*\)\s*\}"""),
        rx("TILE_NOTICE_HAS_THE_PRODUCT_TITLE", "notif", """\boverride\s+fun\s+showTileUnavailable\s*\(\s*\)\s*\{\s*show\s*\([^)]*\bTILE_TITLE\s*,\s*TILE_UNAVAILABLE\b"""),
        rx("MODEL_NOTICES_HAVE_THE_MODEL_TITLE", "notif", """\bMODEL_TITLE\s*,""", 4),
        rx("SHOWN_TITLE_IS_THE_ONE_PASSED_IN", "notif", """\.\s*setContentTitle\s*\(\s*title\s*\)"""),
        rx("SHOWN_TEXT_IS_THE_ONE_PASSED_IN", "notif", """\.\s*setContentText\s*\(\s*text\s*\)"""),
        rx("MISSING_NOTICE_HAS_ITS_OWN_ID_AND_REQUEST", "notif", """\boverride\s+fun\s+showMissing\s*\(\s*\)\s*\{\s*show\s*\(\s*MISSING_ID\s*,\s*MISSING_REQUEST\s*,"""),
        rx("TILE_NOTICE_HAS_ITS_OWN_ID_AND_REQUEST", "notif", """\boverride\s+fun\s+showTileUnavailable\s*\(\s*\)\s*\{\s*show\s*\(\s*UNAVAILABLE_ID\s*,\s*UNAVAILABLE_REQUEST\s*,"""),
        rx("DOWNLOAD_NOTICES_SHARE_THE_DOWNLOAD_ID_AND_REQUEST", "notif", """\bshow\s*\(\s*DOWNLOAD_ID\s*,\s*DOWNLOAD_REQUEST\s*,""", 3),
        rx("ONE_BUTTON_CALLS_THE_DOWNLOAD_ENTRY_POINT", "activity", """\bsetOnClickListener\s*\{\s*app\s*\.\s*tileHost\s*\.\s*requestDownload\s*\(\s*\)\s*\}"""),
        rx("ONE_BUTTON", "activity", """\bButton\s*\(""" ),
        rx("MODEL_ROUTE_FOCUSES_THE_BUTTON", "activity", """\bgetStringExtra\s*\(\s*NotificationRoute\s*\.\s*EXTRA_ROUTE\s*\)\s*==\s*NotificationRoute\s*\.\s*ROUTE_MODEL\s*\)\s*\{\s*download\s*\.\s*isFocusableInTouchMode\s*=\s*true\s+download\s*\.\s*requestFocus\s*\(\s*\)\s*\}"""),
        rx("COLUMN_HOLDS_THE_BUTTON_THEN_THE_SETTINGS_VIEW", "activity", """\bcolumn\s*\.\s*orientation\s*=\s*LinearLayout\s*\.\s*VERTICAL\s+column\s*\.\s*addView\s*\(\s*download\s*\)\s+val\s+settings\s*=\s*dev\s*\.\s*breaker\s*\.\s*dictation\s*\.\s*ui\s*\.\s*createSettingsView\s*\(\s*this\s*,\s*app\s*\.\s*settingsStore\s*\)\s+column\s*\.\s*addView\s*\(\s*settings\s*,\s*LinearLayout\s*\.\s*LayoutParams\s*\(\s*ViewGroup\s*\.\s*LayoutParams\s*\.\s*MATCH_PARENT\s*,\s*0\s*,\s*1f\s*\)\s*\)"""),
    )
    private val firing: List<Sample> = listOf(
        Sample("NOTICE_TAP_IS_AN_IMMUTABLE_ACTIVITY_INTENT", "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT", "PendingIntent.FLAG_UPDATE_CURRENT"),
        Sample("NOTICE_TAP_IS_AN_IMMUTABLE_ACTIVITY_INTENT", "PendingIntent.FLAG_IMMUTABLE or", "PendingIntent.FLAG_MUTABLE or"),
        Sample("ONE_ACTIVITY_PENDING_INTENT", "private fun cancel(id: Int) {", "private fun second(): PendingIntent = PendingIntent.getActivity(context, 9, Intent(), PendingIntent.FLAG_IMMUTABLE)\n\n    private fun cancel(id: Int) {"),
        Sample("ONE_ACTIVITY_PENDING_INTENT", "PendingIntent.getActivity(", "PendingIntent.getBroadcast("),
        Sample("NO_MUTABLE_FLAG", "PendingIntent.FLAG_IMMUTABLE or", "PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_IMMUTABLE or"),
        Sample("NO_MUTABLE_FLAG", "PendingIntent.FLAG_UPDATE_CURRENT)", "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)"),
        Sample("ROUTE_IS_THE_ONLY_EXTRA", "return PendingIntent.getActivity(", "intent.putExtra(\"text\", request)\n        return PendingIntent.getActivity("),
        Sample("ROUTE_IS_THE_ONLY_EXTRA", "if (route != null) intent.putExtra(NotificationRoute.EXTRA_ROUTE, route)\n", ""),
        Sample("ROUTE_EXTRA_IS_THE_ROUTE_NAME", "intent.putExtra(NotificationRoute.EXTRA_ROUTE, route)", "intent.putExtra(\"route\", route)"),
        Sample("ROUTE_EXTRA_IS_THE_ROUTE_NAME", "intent.putExtra(NotificationRoute.EXTRA_ROUTE, route)", "intent.putExtra(NotificationRoute.EXTRA_ROUTE, request)"),
        Sample("MISSING_NOTICE_SAYS_THE_FIXED_SENTENCE_AND_OPENS_THE_MODEL_ROUTE", "ModelSentences.NO_MODEL, NotificationRoute.ROUTE_MODEL", "ModelSentences.NO_MODEL, null"),
        Sample("MISSING_NOTICE_SAYS_THE_FIXED_SENTENCE_AND_OPENS_THE_MODEL_ROUTE", "ModelSentences.NO_MODEL, NotificationRoute.ROUTE_MODEL", "MODEL_READY, NotificationRoute.ROUTE_MODEL"),
        Sample("TILE_NOTICE_OPENS_THE_LAUNCHER_WITHOUT_A_ROUTE", "TILE_UNAVAILABLE, null, false", "TILE_UNAVAILABLE, NotificationRoute.ROUTE_MODEL, false"),
        Sample("TILE_NOTICE_OPENS_THE_LAUNCHER_WITHOUT_A_ROUTE", "TILE_UNAVAILABLE, null, false", "TILE_UNAVAILABLE, null, true"),
        Sample("MODEL_ROUTE_IS_USED_ONCE", "TILE_UNAVAILABLE, null, false", "TILE_UNAVAILABLE, NotificationRoute.ROUTE_MODEL, false"),
        Sample("MODEL_ROUTE_IS_USED_ONCE", "ModelSentences.NO_MODEL, NotificationRoute.ROUTE_MODEL", "ModelSentences.NO_MODEL, NotificationRoute.ROUTE_HISTORY"),
        Sample("CHANNEL_IS_QUIET", "NotificationManager.IMPORTANCE_LOW", "NotificationManager.IMPORTANCE_DEFAULT"),
        Sample("CHANNEL_IS_QUIET", "NotificationManager.IMPORTANCE_LOW", "NotificationManager.IMPORTANCE_HIGH"),
        Sample("CHANNEL_HAS_NO_OTHER_IMPORTANCE", "NotificationManager.IMPORTANCE_LOW))", "NotificationManager.IMPORTANCE_LOW))\n            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)"),
        Sample("CHANNEL_HAS_NO_OTHER_IMPORTANCE", "NotificationManager.IMPORTANCE_LOW", "NotificationManager.IMPORTANCE_MIN"),
        Sample("DENIED_NOTIFICATIONS_SHOW_NOTHING", "if (!manager.areNotificationsEnabled()) return\n", ""),
        Sample("DENIED_NOTIFICATIONS_SHOW_NOTHING", "if (!manager.areNotificationsEnabled()) return", "if (!manager.areNotificationsEnabled()) throw IllegalStateException()"),
        Sample("PLATFORM_REFUSAL_IS_CAUGHT", "} catch (e: RuntimeException) {\n            // The platform refused", "} catch (e: IllegalStateException) {\n            // The platform refused"),
        Sample("PLATFORM_REFUSAL_IS_CAUGHT", "} catch (e: RuntimeException) {\n            // Nothing is left", "} finally {\n            // Nothing is left"),
        Sample("DONE_TAKES_THE_MISSING_NOTICE_AWAY", "cancel(MISSING_ID)\n        show(DOWNLOAD_ID", "show(DOWNLOAD_ID"),
        Sample("DONE_TAKES_THE_MISSING_NOTICE_AWAY", "cancel(MISSING_ID)\n        show(DOWNLOAD_ID", "cancel(UNAVAILABLE_ID)\n        show(DOWNLOAD_ID"),
        Sample("PROGRESS_IS_INDETERMINATE", "setProgress(0, 0, true)", "setProgress(100, 50, false)"),
        Sample("PROGRESS_IS_INDETERMINATE", ".setProgress(0, 0, true)", ""),
        Sample("ONGOING_DICTATION_NOTIFICATION_AND_RESOURCES_ARE_LEFT_ALONE", "val manager = context.getSystemService(NotificationManager::class.java)\n            if", "val manager = context.getSystemService(NotificationManager::class.java)\n            DictationNotification.build(context)\n            if"),
        Sample("ONGOING_DICTATION_NOTIFICATION_AND_RESOURCES_ARE_LEFT_ALONE", ".setContentTitle(title)", ".setContentTitle(context.getString(R.string.dictation_notification_title))"),
        Sample("REQUEST_CODES_DIFFER_AND_ARE_NOT_0_OR_1", "UNAVAILABLE_REQUEST: Int = 3", "UNAVAILABLE_REQUEST: Int = 2"),
        Sample("REQUEST_CODES_DIFFER_AND_ARE_NOT_0_OR_1", "DOWNLOAD_REQUEST: Int = 4", "DOWNLOAD_REQUEST: Int = 0"),
        Sample("NOTIFICATION_IDS_DIFFER_AND_ARE_NOT_1", "MISSING_ID: Int = 2", "MISSING_ID: Int = 1"),
        Sample("NOTIFICATION_IDS_DIFFER_AND_ARE_NOT_1", "DOWNLOAD_ID: Int = 4", "DOWNLOAD_ID: Int = 3"),
        Sample("DOWNLOADING_NOTICE_IS_ONGOING", "ModelSentences.DOWNLOADING, null, true)", "ModelSentences.DOWNLOADING, null, false)"),
        Sample("DOWNLOADING_NOTICE_IS_ONGOING", "ModelSentences.DOWNLOADING, null, true)", "ModelSentences.DOWNLOADING, null, working)"),
        Sample("DONE_NOTICE_IS_NOT_ONGOING", "MODEL_READY, null, false)", "MODEL_READY, null, true)"),
        Sample("DONE_NOTICE_IS_NOT_ONGOING", "MODEL_READY, null, false)", "MODEL_READY, null, !false)"),
        Sample("TILE_NOTICE_HAS_THE_PRODUCT_TITLE", "TILE_TITLE, TILE_UNAVAILABLE", "MODEL_TITLE, TILE_UNAVAILABLE"),
        Sample("TILE_NOTICE_HAS_THE_PRODUCT_TITLE", "TILE_TITLE, TILE_UNAVAILABLE", "TILE_UNAVAILABLE, TILE_TITLE"),
        Sample("MODEL_NOTICES_HAVE_THE_MODEL_TITLE", "ic_btn_speak_now, MODEL_TITLE", "ic_btn_speak_now, TILE_TITLE"),
        Sample("MODEL_NOTICES_HAVE_THE_MODEL_TITLE", "stat_sys_download, MODEL_TITLE", "stat_sys_download, TILE_TITLE"),
        Sample("SHOWN_TITLE_IS_THE_ONE_PASSED_IN", ".setContentTitle(title)", ".setContentTitle(MODEL_TITLE)"),
        Sample("SHOWN_TITLE_IS_THE_ONE_PASSED_IN", ".setContentTitle(title)", ""),
        Sample("SHOWN_TEXT_IS_THE_ONE_PASSED_IN", ".setContentText(text)", ".setContentText(title)"),
        Sample("SHOWN_TEXT_IS_THE_ONE_PASSED_IN", ".setContentText(text)", ".setContentText(MODEL_READY)"),
        Sample("MISSING_NOTICE_HAS_ITS_OWN_ID_AND_REQUEST", "show(MISSING_ID, MISSING_REQUEST,", "show(UNAVAILABLE_ID, MISSING_REQUEST,"),
        Sample("MISSING_NOTICE_HAS_ITS_OWN_ID_AND_REQUEST", "show(MISSING_ID, MISSING_REQUEST,", "show(MISSING_ID, DOWNLOAD_REQUEST,"),
        Sample("TILE_NOTICE_HAS_ITS_OWN_ID_AND_REQUEST", "show(UNAVAILABLE_ID, UNAVAILABLE_REQUEST,", "show(MISSING_ID, UNAVAILABLE_REQUEST,"),
        Sample("TILE_NOTICE_HAS_ITS_OWN_ID_AND_REQUEST", "show(UNAVAILABLE_ID, UNAVAILABLE_REQUEST,", "show(UNAVAILABLE_ID, MISSING_REQUEST,"),
        Sample("DOWNLOAD_NOTICES_SHARE_THE_DOWNLOAD_ID_AND_REQUEST", "show(DOWNLOAD_ID, DOWNLOAD_REQUEST,", "show(MISSING_ID, DOWNLOAD_REQUEST,"),
        Sample("DOWNLOAD_NOTICES_SHARE_THE_DOWNLOAD_ID_AND_REQUEST", "show(DOWNLOAD_ID, DOWNLOAD_REQUEST,", "show(DOWNLOAD_ID, MISSING_REQUEST,"),
        Sample("ONE_BUTTON_CALLS_THE_DOWNLOAD_ENTRY_POINT", "app.tileHost.requestDownload()", "app.armedSwitch.switchOn()"),
        Sample("ONE_BUTTON_CALLS_THE_DOWNLOAD_ENTRY_POINT", "download.setOnClickListener { app.tileHost.requestDownload() }", "download.setOnClickListener { app.tileHost.requestDownload(); app.armedSwitch.switchOff() }"),
        Sample("ONE_BUTTON", "val column = LinearLayout(this)", "val extra = Button(this)\n        val column = LinearLayout(this)"),
        Sample("ONE_BUTTON", "val download = Button(this)", "val download = TextView(this)"),
        Sample("MODEL_ROUTE_FOCUSES_THE_BUTTON", "NotificationRoute.ROUTE_MODEL) {", "NotificationRoute.ROUTE_HISTORY) {"),
        Sample("MODEL_ROUTE_FOCUSES_THE_BUTTON", "download.requestFocus()", "settings.requestFocus()"),
        Sample("COLUMN_HOLDS_THE_BUTTON_THEN_THE_SETTINGS_VIEW", "column.addView(download)\n", ""),
        Sample("COLUMN_HOLDS_THE_BUTTON_THEN_THE_SETTINGS_VIEW", "column.orientation = LinearLayout.VERTICAL", "column.orientation = LinearLayout.HORIZONTAL"),
        Sample("MODEL_ROUTE_FOCUSES_THE_BUTTON", "download.isFocusableInTouchMode = true\n            ", ""),
        Sample("MODEL_ROUTE_FOCUSES_THE_BUTTON", "isFocusableInTouchMode = true", "isFocusableInTouchMode = false"),
        Sample("MODEL_ROUTE_FOCUSES_THE_BUTTON", "download.isFocusableInTouchMode = true\n            download.requestFocus()", "download.requestFocus()\n            download.isFocusableInTouchMode = true"),
        Sample("COLUMN_HOLDS_THE_BUTTON_THEN_THE_SETTINGS_VIEW", ", 0, 1f))", ", 0, 0f))"),
        Sample("COLUMN_HOLDS_THE_BUTTON_THEN_THE_SETTINGS_VIEW", "MATCH_PARENT, 0, 1f", "WRAP_CONTENT, 0, 1f"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("notif", "private fun cancel(id: Int) {", "/* getActivity( putExtra( FLAG_MUTABLE IMPORTANCE_HIGH R.string getString */\n    private fun cancel(id: Int) {"),
        Quiet("notif", "const val MISSING_ID: Int = 2", "const val MISSING_ID: Int = 12"),
        Quiet("notif", "MODEL_READY, null, false)", "MODEL_READY, null, false) // true"),
        Quiet("notif", ".setContentTitle(title)", ".setContentTitle(  title  )"),
        Quiet("notif", ".setContentText(text)", ".setContentText(  text  ) // setContentText(title)"),
        Quiet("notif", "show(UNAVAILABLE_ID, UNAVAILABLE_REQUEST,", "show(UNAVAILABLE_ID, UNAVAILABLE_REQUEST, // MISSING_ID,\n            "),
        Quiet("activity", "download.requestFocus()", "download.requestFocus() // Button( requestDownload"),
        Quiet("activity", "download.isFocusableInTouchMode = true", "download.isFocusableInTouchMode = true // = false"),
        Quiet("activity", ", 0, 1f))", ",\n            0, 1f)\n        )"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a host gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }
    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))
    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code

    /** The value of `const val [name]: String = "..."`, read from the raw text because the scanner blanks literals. */
    private fun titleOf(text: String, name: String): String? =
        Regex("""(?m)^\s*const\s+val\s+$name\s*:\s*String\s*=\s*"([^"]*)"""").find(text)?.groupValues?.get(1)
    private fun titlesAreSeparate(text: String): Boolean {
        val tile = titleOf(text, "TILE_TITLE")
        val model = titleOf(text, "MODEL_TITLE")
        return !tile.isNullOrBlank() && !model.isNullOrBlank() && tile != model && !model.contains(tile) && !tile.contains(model)
    }

    @Test
    fun `the tile title and the model title are two different words`() {
        assertTrue("app: the tile notice and the model notices must carry different titles", titlesAreSeparate(raw("notif")))
        val edits = listOf(
            "TILE_TITLE: String = \"Breaker\"" to "TILE_TITLE: String = \"Speech model\"",
            "TILE_TITLE: String = \"Breaker\"" to "TILE_TITLE: String = \"\"",
            "MODEL_TITLE: String = \"Speech model\"" to "MODEL_TITLE: String = \"Breaker\"",
        )
        for ((old, new) in edits) assertFalse("app: the titles must be flagged after '$old' => '$new'", titlesAreSeparate(edit(raw("notif"), old, new)))
        val reworded = edit(raw("notif"), "TILE_TITLE: String = \"Breaker\"", "TILE_TITLE: String = \"Breaker app\"")
        assertTrue("app: a different tile title is harmless", titlesAreSeparate(reworded))
        val commented = edit(raw("notif"), "const val TILE_TITLE", "// const val TILE_TITLE: String = \"Speech model\"\n        const val TILE_TITLE")
        assertTrue("app: a commented definition is not read", titlesAreSeparate(commented))
    }

    @Test
    fun `every rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a host rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: host rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse("app: host rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(rule.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: host rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) { edit("val a = 1", "no such text", "x") }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
