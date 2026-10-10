package dev.breaker.dictation.service

import dev.breaker.dictation.gates.AppSourceFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification code is Android code that no JVM test runs, so a text gate holds it to the rules of
 * the tap: the content intent is a pending activity, aimed by class at the launcher activity (explicit, no
 * action text), carries the named history route through the route constants, and is immutable; the one
 * "switch off" action is untouched and still asks the service to disarm; the notification stays ongoing and
 * never cancels itself on a tap. The gate reads code only: comments and string literals are removed first
 * (the small stripper below is a near copy of the one in the service adapter gate). Each rule has firing
 * samples and a quiet sample, and the check on the real file fails by name when the file is missing.
 */
internal class NotificationContentGateTest {

    private val notificationFile = "kotlin/dev/breaker/dictation/service/DictationNotification.kt"

    private fun read(): String = AppSourceFiles.mainFile(notificationFile)

    /** Source without comments and without the text of strings and characters; refuses text it cannot read. */
    private fun strip(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("//", i)) {
                while (i < text.length && text[i] != '\n') i++
            } else if (text.startsWith("/*", i)) {
                var depth = 0
                do {
                    if (text.startsWith("/*", i)) { depth++; i += 2 }
                    else if (text.startsWith("*/", i)) { depth--; i += 2 }
                    else { if (text[i] == '\n') out.append('\n'); i++ }
                } while (depth > 0 && i < text.length)
                check(depth == 0) { "app: a block comment is never closed" }
                out.append(' ')
            } else if (text.startsWith("\"\"\"", i)) {
                val end = text.indexOf("\"\"\"", i + 3)
                check(end >= 0) { "app: a raw string is never closed" }
                i = end + 3
                out.append("\"\"")
            } else if (text[i] == '"') {
                i++
                while (i < text.length && text[i] != '"') {
                    check(text[i] != '\n') { "app: a string literal is never closed" }
                    i += if (text[i] == '\\') 2 else 1
                }
                check(i < text.length) { "app: a string literal is never closed" }
                i++
                out.append("\"\"")
            } else if (text[i] == '\'') {
                val end = text.indexOf('\'', i + if (text.getOrNull(i + 1) == '\\') 3 else 2)
                check(end >= 0) { "app: a character literal is never closed" }
                i = end + 1
                out.append("''")
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** The text between the parentheses that open at index [open] of [code], or null when they never close. */
    private fun argsAt(code: String, open: Int): String? {
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '(') depth++
            if (code[i] == ')') {
                depth--
                if (depth == 0) return code.substring(open + 1, i)
            }
            i++
        }
        return null
    }

    /** [args] cut at the commas that are not inside brackets, trimmed, empty pieces (a trailing comma) dropped. */
    private fun topLevel(args: String): List<String> {
        val parts: MutableList<String> = ArrayList()
        var depth = 0
        var start = 0
        for (i in args.indices) {
            val c = args[i]
            if (c == '(' || c == '{' || c == '[') depth++
            else if (c == ')' || c == '}' || c == ']') depth--
            else if (c == ',' && depth == 0) { parts.add(args.substring(start, i).trim()); start = i + 1 }
        }
        parts.add(args.substring(start).trim())
        return parts.filter { it.isNotEmpty() }
    }

    /** The argument lists of every call whose opening text matches [head] (a pattern that ends at the parenthesis). */
    private fun callsOf(code: String, head: Regex): List<List<String>> =
        head.findAll(code).mapNotNull { m -> argsAt(code, m.range.last)?.let { topLevel(it) } }.toList()

    private val activityHead = Regex("\\bPendingIntent\\s*\\.\\s*getActivity\\s*\\(")
    private val contentHead = Regex("\\bsetContentIntent\\s*\\(")
    private val addActionHead = Regex("\\baddAction\\s*\\(")
    private val actionMakerHead = Regex("\\b(?:Notification\\s*\\.\\s*)?Action\\s*\\.\\s*Builder\\s*\\(")
    private val actionMakerVal = Regex("\\bval\\s+(\\w+)\\s*=\\s*(?:Notification\\s*\\.\\s*)?Action\\s*\\.\\s*Builder\\s*\\(")
    private val serviceVal = Regex("\\bval\\s+(\\w+)\\s*=\\s*PendingIntent\\s*\\.\\s*getService\\s*\\(")
    private val explicitIntent = Regex("^Intent\\s*\\(\\s*\\w+\\s*,\\s*SettingsLauncherActivity\\s*::\\s*class\\s*\\.\\s*java\\s*\\)")
    private val anyIntent = Regex("\\bIntent\\s*\\(")
    private val actionText = Regex("\\bsetAction\\b|\\.\\s*action\\b|\\bsetComponent\\b|\\bsetClassName\\b|\\bsetPackage\\b")
    private val anyExtra = Regex("\\bputExtra\\s*\\(")
    private val routeExtra = Regex(
        "\\.\\s*putExtra\\s*\\(\\s*NotificationRoute\\s*\\.\\s*EXTRA_ROUTE\\s*,\\s*NotificationRoute\\s*\\.\\s*ROUTE_HISTORY\\s*\\)",
    )
    private val newTask = Regex("\\bIntent\\s*\\.\\s*FLAG_ACTIVITY_NEW_TASK\\b")
    private val clearTop = Regex("\\bIntent\\s*\\.\\s*FLAG_ACTIVITY_CLEAR_TOP\\b")
    private val immutable = Regex("\\bPendingIntent\\s*\\.\\s*FLAG_IMMUTABLE\\b")
    private val mutable = Regex("\\bFLAG_MUTABLE\\b")
    private val autoCancel = Regex("\\bsetAutoCancel\\s*\\(\\s*(?!false\\s*\\))")
    private val ongoing = Regex("\\.\\s*setOngoing\\s*\\(\\s*true\\s*\\)")

    private val rules = listOf(
        "CONTENT_INTENT", "INTENT_EXPLICIT", "INTENT_FLAGS", "ROUTE_EXTRA",
        "FLAGS_IMMUTABLE", "ONE_ACTION", "NO_AUTOCANCEL", "ONGOING",
    )

    /** The names of the rules [source] breaks, in the order of [rules]. */
    private fun problems(source: String): List<String> {
        val code = strip(source)
        val bad: MutableList<String> = ArrayList()
        val activityCalls = callsOf(code, activityHead)
        val activity: List<String> = if (activityCalls.size == 1) activityCalls[0] else emptyList()
        val intent: String = activity.getOrNull(2) ?: ""
        val flags: String = activity.getOrNull(3) ?: ""

        val contentArg: String? = callsOf(code, contentHead).singleOrNull()?.singleOrNull()
        val direct = contentArg != null && activityHead.find(contentArg)?.range?.first == 0
        val named = contentArg != null && Regex("[A-Za-z_]\\w*").matches(contentArg) &&
            Regex("\\bval\\s+" + Regex.escape(contentArg) + "\\s*=\\s*PendingIntent\\s*\\.\\s*getActivity\\s*\\(").containsMatchIn(code)
        if (activityCalls.size != 1 || !(direct || named)) bad.add("CONTENT_INTENT")

        val explicit = explicitIntent.containsMatchIn(intent) && anyIntent.findAll(intent).count() == 1 && !actionText.containsMatchIn(intent)
        if (!explicit) bad.add("INTENT_EXPLICIT")
        if (!(newTask.containsMatchIn(intent) && clearTop.containsMatchIn(intent) && intent.contains("addFlags"))) bad.add("INTENT_FLAGS")
        if (routeExtra.findAll(intent).count() != 1 || anyExtra.findAll(intent).count() != 1) bad.add("ROUTE_EXTRA")
        if (activity.size != 4 || !immutable.containsMatchIn(flags) || mutable.containsMatchIn(flags)) bad.add("FLAGS_IMMUTABLE")

        val makers = callsOf(code, actionMakerHead)
        val adds = callsOf(code, addActionHead)
        val actionVal: String? = actionMakerVal.find(code)?.groupValues?.get(1)
        val switchVal: String? = makers.singleOrNull()?.getOrNull(2)
        val switchArgs: String? = serviceVal.findAll(code)
            .firstOrNull { it.groupValues[1] == switchVal }
            ?.let { argsAt(code, it.range.last) }
        val disarms = switchArgs != null &&
            Regex("\\bACTION_DISARM\\b").containsMatchIn(switchArgs) &&
            Regex("\\bDictationForegroundService\\s*::\\s*class\\s*\\.\\s*java\\b").containsMatchIn(switchArgs)
        val oneAction = makers.size == 1 && adds.size == 1 && actionVal != null && adds[0] == listOf(actionVal)
        if (!(oneAction && disarms)) bad.add("ONE_ACTION")

        if (autoCancel.containsMatchIn(code)) bad.add("NO_AUTOCANCEL")
        if (!ongoing.containsMatchIn(code)) bad.add("ONGOING")
        return bad
    }

    private val sample = """
        package x
        internal object DictationNotification {
            fun build(context: Context): Notification {
                val switchOff = PendingIntent.getService(
                    context,
                    0,
                    Intent(context, DictationForegroundService::class.java).setAction(ACTION_DISARM),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val action = Notification.Action.Builder(
                    Icon.createWithResource(context, icon),
                    context.getString(R.string.dictation_action_off),
                    switchOff,
                ).build()
                val openHistory = PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, SettingsLauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(NotificationRoute.EXTRA_ROUTE, NotificationRoute.ROUTE_HISTORY),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                return Notification.Builder(context, CHANNEL_ID)
                    .setContentIntent(openHistory)
                    .setOngoing(true)
                    .addAction(action)
                    .build()
            }
        }
    """.trimIndent()

    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "app: a gate sample lost its text '$old'" }
        return text.replace(old, new)
    }

    private fun assertRealFilePasses(rule: String) =
        assertTrue("app: DictationNotification.kt breaks rule $rule: ${problems(read())}", problems(read()).none { it == rule })

    @Test
    fun `the content intent is a pending activity`() = assertRealFilePasses("CONTENT_INTENT")

    @Test
    fun `the content intent targets the launcher activity by class and names no action`() = assertRealFilePasses("INTENT_EXPLICIT")

    @Test
    fun `the content intent opens a new task or brings the old one forward`() = assertRealFilePasses("INTENT_FLAGS")

    @Test
    fun `the content intent carries the history route through the route constants`() = assertRealFilePasses("ROUTE_EXTRA")

    @Test
    fun `the content pending intent is immutable`() = assertRealFilePasses("FLAGS_IMMUTABLE")

    @Test
    fun `the notification has exactly one action and it asks the service to disarm`() = assertRealFilePasses("ONE_ACTION")

    @Test
    fun `the notification does not cancel itself on a tap`() = assertRealFilePasses("NO_AUTOCANCEL")

    @Test
    fun `the notification stays ongoing`() = assertRealFilePasses("ONGOING")

    @Test
    fun `the unedited sample and the quiet variants break no rule`() {
        assertEquals("app: the sample was reported", emptyList<String>(), problems(sample))
        assertEquals("app: setAutoCancel(false) was reported", emptyList<String>(), problems(edit(sample, ".setOngoing(true)", ".setOngoing(true).setAutoCancel(false)")))
        val prose = sample + "\n// setAutoCancel(true) addAction(x) setContentIntent(y)\n/* getActivity( putExtra( */\nval s = \"setAutoCancel(true) addAction(z)\"\n"
        assertEquals("app: names in comments and strings were reported", emptyList<String>(), problems(prose))
    }

    @Test
    fun `an edited copy that breaks one thing is reported under the rules that guard it`() {
        val firing: List<Triple<String, String, List<String>>> = listOf(
            Triple("no content intent", edit(sample, "setContentIntent(openHistory)", "setSubText(openHistory)"), listOf("CONTENT_INTENT")),
            Triple("content intent is the switch off", edit(sample, "setContentIntent(openHistory)", "setContentIntent(switchOff)"), listOf("CONTENT_INTENT")),
            Triple(
                "content intent is a service",
                edit(sample, "PendingIntent.getActivity(", "PendingIntent.getService("),
                listOf("CONTENT_INTENT", "INTENT_EXPLICIT", "INTENT_FLAGS", "ROUTE_EXTRA", "FLAGS_IMMUTABLE"),
            ),
            Triple("other class", edit(sample, "SettingsLauncherActivity::class.java", "OtherActivity::class.java"), listOf("INTENT_EXPLICIT")),
            Triple(
                "an action text",
                edit(sample, "Intent(context, SettingsLauncherActivity::class.java)", "Intent(context, SettingsLauncherActivity::class.java).setAction(ACTION_ARM)"),
                listOf("INTENT_EXPLICIT"),
            ),
            Triple("implicit intent", edit(sample, "Intent(context, SettingsLauncherActivity::class.java)", "Intent(ACTION_ARM)"), listOf("INTENT_EXPLICIT")),
            Triple("no new task flag", edit(sample, "Intent.FLAG_ACTIVITY_NEW_TASK or ", ""), listOf("INTENT_FLAGS")),
            Triple("no clear top flag", edit(sample, " or Intent.FLAG_ACTIVITY_CLEAR_TOP", ""), listOf("INTENT_FLAGS")),
            Triple("route literal", edit(sample, "NotificationRoute.ROUTE_HISTORY", "\"history\""), listOf("ROUTE_EXTRA")),
            Triple("extra name literal", edit(sample, "NotificationRoute.EXTRA_ROUTE", "\"route\""), listOf("ROUTE_EXTRA")),
            Triple(
                "a second extra",
                edit(sample, "NotificationRoute.ROUTE_HISTORY)", "NotificationRoute.ROUTE_HISTORY).putExtra(a, b)"),
                listOf("ROUTE_EXTRA"),
            ),
            Triple(
                "no extra",
                edit(sample, ".putExtra(NotificationRoute.EXTRA_ROUTE, NotificationRoute.ROUTE_HISTORY)", ""),
                listOf("ROUTE_EXTRA"),
            ),
            Triple(
                "not immutable",
                edit(sample, "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT", "PendingIntent.FLAG_UPDATE_CURRENT"),
                listOf("FLAGS_IMMUTABLE"),
            ),
            Triple(
                "mutable",
                edit(sample, "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT", "PendingIntent.FLAG_MUTABLE"),
                listOf("FLAGS_IMMUTABLE"),
            ),
            Triple("a second action", edit(sample, ".addAction(action)", ".addAction(action).addAction(action)"), listOf("ONE_ACTION")),
            Triple("no action", edit(sample, ".addAction(action)", ".setSubText(action)"), listOf("ONE_ACTION")),
            Triple("action arms", edit(sample, ".setAction(ACTION_DISARM)", ".setAction(ACTION_ARM)"), listOf("ONE_ACTION")),
            Triple("auto cancel", edit(sample, ".setOngoing(true)", ".setOngoing(true).setAutoCancel(true)"), listOf("NO_AUTOCANCEL")),
            Triple("auto cancel by value", edit(sample, ".setOngoing(true)", ".setOngoing(true).setAutoCancel(flag)"), listOf("NO_AUTOCANCEL")),
            Triple("not ongoing", edit(sample, ".setOngoing(true)", ".setOngoing(false)"), listOf("ONGOING")),
        )
        for ((label, text, expected) in firing) {
            assertEquals("app: the gate missed or mislabelled the firing sample '$label'", expected, problems(text))
        }
        val covered: Set<String> = firing.flatMap { it.third }.toSet()
        assertEquals("app: a rule has no firing sample", rules.toSet(), covered)
    }

    @Test
    fun `a file that is missing or cannot be read as code is refused by name`() {
        val missing = assertThrows("app: a missing file must fail", IllegalStateException::class.java) { AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/service/Nope.kt") }
        assertTrue("app: the missing-file failure must name the file: ${missing.message}", missing.message.orEmpty().contains("Nope.kt"))
        assertThrows("app: an unterminated string must be refused", IllegalStateException::class.java) { strip("val s = \"never ends\n}") }
        assertThrows("app: an unterminated comment must be refused", IllegalStateException::class.java) { strip("class A /* never closed\n") }
        assertTrue("app: the gate read no code in the notification file", strip(read()).isNotBlank())
    }
}
