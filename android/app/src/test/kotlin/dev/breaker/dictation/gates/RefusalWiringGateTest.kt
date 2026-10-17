package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring of a refused foreground, which no JVM test runs: the notice is posted under the ongoing id, the
 * permission is the notification manager's own answer, the sentence is held in the application and the notice
 * is built from the application's own holder, and the service hands its refusal to that notice. The rules read
 * the real files with comments and literal text removed; each rule holds on the real file, is broken by at least
 * two edited samples, and stays quiet on a harmless edit. An edit whose target text is missing fails by name.
 */
internal class RefusalWiringGateTest {

    private val dir: String = "kotlin/dev/breaker/dictation/"
    private val files: Map<String, String> = mapOf(
        "notif" to dir + "service/DictationNotification.kt",
        "app" to dir + "BreakerApp.kt",
        "service" to dir + "service/DictationForegroundService.kt",
        "activity" to dir + "SettingsLauncherActivity.kt",
    )

    private class Rule(val name: String, val file: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val file: String, val old: String, val new: String)
    private class Quiet(val file: String, val old: String, val new: String)

    /** The pattern must match the code exactly [times] times. */
    private fun rx(name: String, file: String, pattern: String, times: Int = 1) =
        Rule(name, file) { Regex(pattern).findAll(it).count() == times }

    private val rules: List<Rule> = listOf(
        rx("POST_NOTIFIES_THE_REFUSAL_UNDER_THE_ONGOING_ID", "notif",
            """\bfun\s+postRefusal\s*\(\s*context\s*:\s*Context\s*,\s*sentence\s*:\s*String\s*\)\s*\{\s*context\s*\.\s*getSystemService\s*\(\s*NotificationManager\s*::\s*class\s*\.\s*java\s*\)\s*\.\s*notify\s*\(\s*NOTIFICATION_ID\s*,\s*refusal\s*\(\s*context\s*,\s*sentence\s*\)\s*\)\s*\}"""),
        rx("PERMISSION_IS_THE_NOTIFICATION_MANAGERS_ANSWER", "notif",
            """\bfun\s+notificationsPermitted\s*\(\s*context\s*:\s*Context\s*\)\s*:\s*Boolean\s*=\s*context\s*\.\s*getSystemService\s*\(\s*NotificationManager\s*::\s*class\s*\.\s*java\s*\)\s*\.\s*areNotificationsEnabled\s*\(\s*\)"""),
        rx("APP_HOLDS_THE_REFUSAL_AND_FEEDS_THE_HOLD", "app",
            """\binternal\s+val\s+refusalHold\s*:\s*RefusalHold\s*=\s*RefusalHold\s*\(\s*\)\s*internal\s+val\s+serviceRefusal\s*:\s*ServiceRefusalNotice\s+by\s+lazy\s*\{\s*ServiceRefusalNotice\s*\(\s*hold\s*=\s*\{\s*sentence\s*->\s*refusalHold\s*\.\s*set\s*\(\s*sentence\s*\)\s*\}"""),
        rx("APP_ASKS_THE_PERMISSION_AND_POSTS_THROUGH_THE_NOTICE", "app",
            """notificationPermitted\s*=\s*\{\s*DictationNotification\s*\.\s*notificationsPermitted\s*\(\s*this\s*\)\s*\}\s*,\s*postNotice\s*=\s*\{\s*sentence\s*->\s*DictationNotification\s*\.\s*postRefusal\s*\(\s*this\s*,\s*sentence\s*\)\s*\}"""),
        rx("SERVICE_SENDS_ITS_REFUSAL_TO_THE_APP_NOTICE", "service",
            """\bServiceStartHandler\s*\(\s*controller\s*,\s*host\s*,\s*\{\s*sentence\s*->\s*\(\s*application\s+as\s+BreakerApp\s*\)\s*\.\s*serviceRefusal\s*\.\s*refuse\s*\(\s*sentence\s*\)\s*\}\s*\)"""),
        rx("ONRESUME_TAKES_THE_HELD_REFUSAL_AFTER_THE_REFRESH_AND_SHOWS_IT_ONCE", "activity",
            """\(onboardingView\s*as\s*\?\s*ModelStepControl\s*\)\s*\?\.\s*refresh\s*\(\s*\)\s*val\s+held\s*=\s*app\s*\.\s*refusalHold\s*\.\s*take\s*\(\s*\)\s*val\s+refusal\s*=\s*pendingRefusal\s*\?:\s*held\s*pendingRefusal\s*=\s*null\s*refusal\s*\?\.\s*let\s*\{\s*\(onboardingView\s*as\s*\?\s*ModelStepControl\s*\)\s*\?\.\s*showSwitchRefused\s*\(\s*it\s*\)\s*\}\s*\}"""),
    )

    private val firing: List<Sample> = listOf(
        Sample("ONRESUME_TAKES_THE_HELD_REFUSAL_AFTER_THE_REFRESH_AND_SHOWS_IT_ONCE", "activity", "(onboardingView as? ModelStepControl)?.refresh()\n        val held = app.refusalHold.take()", "val held = app.refusalHold.take()\n        (onboardingView as? ModelStepControl)?.refresh()"),
        Sample("ONRESUME_TAKES_THE_HELD_REFUSAL_AFTER_THE_REFRESH_AND_SHOWS_IT_ONCE", "activity", "pendingRefusal = null\n", ""),
        Sample("ONRESUME_TAKES_THE_HELD_REFUSAL_AFTER_THE_REFRESH_AND_SHOWS_IT_ONCE", "activity", "refusal?.let { (onboardingView as? ModelStepControl)?.showSwitchRefused(it) }", ""),
        Sample("POST_NOTIFIES_THE_REFUSAL_UNDER_THE_ONGOING_ID", "notif", "notify(NOTIFICATION_ID, refusal(context, sentence))", "Unit"),
        Sample("POST_NOTIFIES_THE_REFUSAL_UNDER_THE_ONGOING_ID", "notif", "notify(NOTIFICATION_ID, refusal(context, sentence))", "notify(MISSING_ID, refusal(context, sentence))"),
        Sample("PERMISSION_IS_THE_NOTIFICATION_MANAGERS_ANSWER", "notif", "areNotificationsEnabled()", "areNotificationsEnabled(context)"),
        Sample("PERMISSION_IS_THE_NOTIFICATION_MANAGERS_ANSWER", "notif", "areNotificationsEnabled()", "isPermitted()"),
        Sample("APP_HOLDS_THE_REFUSAL_AND_FEEDS_THE_HOLD", "app", "RefusalHold = RefusalHold()", "RefusalHold = RefusalHold(this)"),
        Sample("APP_HOLDS_THE_REFUSAL_AND_FEEDS_THE_HOLD", "app", "hold = { sentence -> refusalHold.set(sentence) }", "hold = { sentence -> }"),
        Sample("APP_ASKS_THE_PERMISSION_AND_POSTS_THROUGH_THE_NOTICE", "app", "notificationPermitted = { DictationNotification.notificationsPermitted(this) }", "notificationPermitted = { true }"),
        Sample("APP_ASKS_THE_PERMISSION_AND_POSTS_THROUGH_THE_NOTICE", "app", "postNotice = { sentence -> DictationNotification.postRefusal(this, sentence) }", "postNotice = { sentence -> }"),
        Sample("SERVICE_SENDS_ITS_REFUSAL_TO_THE_APP_NOTICE", "service", "ServiceStartHandler(controller, host, { sentence -> (application as BreakerApp).serviceRefusal.refuse(sentence) })", "ServiceStartHandler(controller, host)"),
        Sample("SERVICE_SENDS_ITS_REFUSAL_TO_THE_APP_NOTICE", "service", "{ sentence -> (application as BreakerApp).serviceRefusal.refuse(sentence) }", "{ sentence -> }"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("notif", "fun postRefusal(context: Context, sentence: String) {", "fun postRefusal(context: Context, sentence: String) { // posts the notice"),
        Quiet("notif", "areNotificationsEnabled()", "areNotificationsEnabled() // not isPermitted()"),
        Quiet("app", "hold = { sentence -> refusalHold.set(sentence) }", "hold = { sentence -> refusalHold.set(sentence) } /* not a hold */"),
        Quiet("service", "{ sentence -> (application as BreakerApp).serviceRefusal.refuse(sentence) }", "{ sentence -> (application as BreakerApp).serviceRefusal.refuse(sentence) /* refuse */ }"),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a refusal wiring sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun raw(key: String): String = AppSourceFiles.mainFile(files.getValue(key))

    private fun codeOf(key: String, old: String? = null, new: String = ""): String =
        AppSourceFiles.strip(if (old == null) raw(key) else edit(raw(key), old, new)).code

    @Test
    fun `every refusal wiring rule holds on the real files`() {
        for (key in files.keys) assertTrue("app: ${files.getValue(key)} was read as empty", codeOf(key).isNotBlank())
        for (rule in rules) assertTrue("app: ${files.getValue(rule.file)} breaks rule ${rule.name}", rule.holds(codeOf(rule.file)))
    }

    @Test
    fun `the firing samples cover each refusal wiring rule at least twice`() {
        val names = rules.map { it.name }
        assertEquals("app: a refusal wiring rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: refusal wiring rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every refusal wiring rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertEquals("app: sample for ${sample.rule} names the wrong file", rule.file, sample.file)
            assertFalse("app: refusal wiring rule ${sample.rule} must fire on the edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
        }
    }

    @Test
    fun `no refusal wiring rule fires on a harmless edit`() {
        for (sample in quiet) {
            for (rule in rules.filter { it.file == sample.file }) {
                assertTrue("app: refusal wiring rule ${rule.name} must stay quiet on the harmless edit '${sample.old}' => '${sample.new}'", rule.holds(codeOf(sample.file, sample.old, sample.new)))
            }
        }
    }

    @Test
    fun `a sample whose target text is missing fails by name`() {
        val thrown = runCatching { edit("val a = 1", "no such text", "x") }.exceptionOrNull()
        assertTrue("app: a missing target must fail by name, got ${thrown?.message}", thrown?.message.orEmpty().contains("no such text"))
    }
}
