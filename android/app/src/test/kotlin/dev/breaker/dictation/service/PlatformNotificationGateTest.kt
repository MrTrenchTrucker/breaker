package dev.breaker.dictation.service

import org.junit.Test

private typealias Rule = PlatformAdapterGateTest.Rule

/**
 * The notification of the microphone service runs on no JVM test, so a text gate holds the facts its builder
 * carries: the channel is quiet and is created with a name from a string resource, the switch-off pending
 * intent is immutable, the title and the one content line come from string resources (the content line
 * through the one text parameter of the shared builder), the switch-off label comes from a string resource,
 * the notification holds no literal text other than the channel id, and it is ongoing. Rules read code only
 * (comments and literal text are removed by the shared scanner). Each rule holds on the real file, is broken
 * by at least one edited sample and stays quiet on a harmless edit; a sample whose target text is missing
 * fails by name, so it cannot go quiet by a typo.
 */
internal class PlatformNotificationGateTest {

    private val gate = PlatformAdapterGateTest()

    private fun args(code: String, call: String): List<String> = gate.args(code, call)

    private fun has(text: String, pattern: String): Boolean = gate.has(text, pattern)

    private fun runGate(file: String, rules: List<Rule>, firing: Map<String, List<String>>, quiet: List<String>) =
        gate.runGate(file, rules, firing, quiet)

    private fun res(name: String): String =
        """context\s*\.\s*getString\s*\(\s*R\s*\.\s*string\s*\.\s*$name\s*\)"""

    // ---- DictationNotification.kt ----

    private val notificationRules = listOf(
        Rule("CHANNEL_QUIET") { s ->
            val a = args(s.code, """\bNotificationChannel""")
            a.size == 1 && has(a[0], """,\s*NotificationManager\s*\.\s*IMPORTANCE_LOW\s*,?\s*\z""")
        },
        Rule("CHANNEL_CREATED") { s ->
            val a = args(s.code, """\b\w+\s*\.\s*createNotificationChannel""")
            a.size == 1 && has(a[0], """\bNotificationChannel\s*\(""")
        },
        Rule("CHANNEL_NAME_FROM_RESOURCE") { s ->
            val a = args(s.code, """\bNotificationChannel""")
            a.size == 1 && has(a[0], """,\s*${res("dictation_channel_name")}\s*,""")
        },
        Rule("SWITCH_OFF_IMMUTABLE") { s ->
            val a = args(s.code, """\bPendingIntent\s*\.\s*getService""")
            a.size == 1 && has(a[0], """\bPendingIntent\s*\.\s*FLAG_IMMUTABLE\b""")
        },
        Rule("TITLE_FROM_RESOURCE") { s ->
            has(s.code, """\.\s*setContentTitle\s*\(\s*${res("dictation_notification_title")}\s*\)""")
        },
        Rule("TEXT_FROM_RESOURCE_IN_BUILD") { s ->
            has(
                s.code,
                """\bfun\s+build\s*\(\s*context\s*:\s*Context\s*\)\s*:\s*Notification\s*=\s*withText\s*\(\s*context\s*,\s*""" +
                    res("dictation_notification_text") + """\s*,?\s*\)""",
            )
        },
        Rule("TEXT_ONE_LINE_SOURCE") { s ->
            val a = args(s.code, """\.\s*setContentText""")
            a.size == 1 && has(a[0], """\A\s*textLine\s*\(\s*\)\s*,?\s*\z""") &&
                has(
                    s.code,
                    """\bfun\s+withText\s*\(\s*context\s*:\s*Context\s*,\s*text\s*:\s*String\s*\)\s*:\s*Notification\s*=""" +
                        """\s*builder\s*\(\s*context\s*\)\s*\{\s*text\s*\}""",
                )
        },
        Rule("ACTION_LABEL_FROM_RESOURCE") { s ->
            val a = args(s.code, """\bNotification\s*\.\s*Action\s*\.\s*Builder""")
            a.size == 1 && has(a[0], res("dictation_action_off"))
        },
        Rule("CHANNEL_ID_IS_THE_ONLY_LITERAL") { s -> s.literals == listOf("dictation") },
        Rule("ONGOING") { s -> has(s.code, """\.\s*setOngoing\s*\(\s*true\s*\)""") },
    )

    private val low = "NotificationManager.IMPORTANCE_LOW"
    private val flags = "PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,"
    private val notificationFiring: Map<String, List<String>> = mapOf(
        "CHANNEL_QUIET" to listOf(
            "$low => NotificationManager.IMPORTANCE_HIGH",
            "$low => NotificationManager.IMPORTANCE_DEFAULT",
            "$low => NotificationManager.IMPORTANCE_MIN",
            "$low => NotificationManager.IMPORTANCE_NONE",
        ),
        "CHANNEL_CREATED" to listOf(
            "manager.createNotificationChannel( => manager.deleteNotificationChannel(",
            "manager.createNotificationChannel( => // manager.createNotificationChannel(",
        ),
        "CHANNEL_NAME_FROM_RESOURCE" to listOf("context.getString(R.string.dictation_channel_name) => \"Dictation\""),
        "SWITCH_OFF_IMMUTABLE" to listOf(
            "$flags => PendingIntent.FLAG_UPDATE_CURRENT, @0",
            "$flags => PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, @0",
            "PendingIntent.getService( => PendingIntent.getForegroundService(",
        ),
        "TITLE_FROM_RESOURCE" to listOf(
            "context.getString(R.string.dictation_notification_title) => \"Breaker is on\"",
            "context.getString(R.string.dictation_notification_title) => context.getString(R.string.dictation_notification_text)",
        ),
        "TEXT_FROM_RESOURCE_IN_BUILD" to listOf(
            "withText(context, context.getString(R.string.dictation_notification_text)) => withText(context, \"Tap the tile\")",
            "withText(context, context.getString(R.string.dictation_notification_text)) => " +
                "withText(context, context.getString(R.string.dictation_action_off))",
            "withText(context, context.getString(R.string.dictation_notification_text)) => " +
                "withText(context, context.getString(R.string.dictation_notification_text).trim())",
        ),
        "TEXT_ONE_LINE_SOURCE" to listOf(
            ".setContentText(textLine()) => .setContentText(\"Tap the tile\")",
            ".setContentText(textLine()) => .setContentText(context.getString(R.string.dictation_notification_text))",
            ".setContentText(textLine()) => .setContentText(context.getString(R.string.dictation_action_off))",
            ".setContentText(textLine()) => ",
            "builder(context) { text } => builder(context) { \"Tap the tile\" }",
            "builder(context) { text } => builder(context) { context.getString(R.string.dictation_action_off) }",
            "builder(context) { text } => builder(context) { text + context.getString(R.string.dictation_action_off) }",
        ),
        "ACTION_LABEL_FROM_RESOURCE" to listOf("context.getString(R.string.dictation_action_off) => \"Switch off\""),
        "CHANNEL_ID_IS_THE_ONLY_LITERAL" to listOf(
            "context.getString(R.string.dictation_notification_title) => \"Breaker is on\"",
            "context.getString(R.string.dictation_action_off) => \"Switch off\"",
        ),
        "ONGOING" to listOf(
            ".setOngoing(true) => .setOngoing(false)",
            ".setOngoing(true) => .setAutoCancel(true)",
            ".setOngoing(true) => // .setOngoing(true)",
        ),
    )

    private val notificationQuiet = listOf(
        "val icon => // NotificationManager.IMPORTANCE_HIGH, setOngoing(false)\n        val icon",
        "NotificationManager.IMPORTANCE_LOW => NotificationManager\n                .IMPORTANCE_LOW",
        ".setContentTitle(context => .setContentTitle(\n                context",
        "withText(context, context.getString(R.string.dictation_notification_text)) => withText(\n" +
            "            context,\n            context.getString(R.string.dictation_notification_text),\n        )",
        ".setContentText(textLine()) => .setContentText(\n                textLine(),\n            )",
        "builder(context) { text } => builder(context) {\n            text\n        }",
    )

    @Test
    fun `the notification channel is quiet and created, its words come from resources and it is ongoing`() =
        runGate("DictationNotification.kt", notificationRules, notificationFiring, notificationQuiet)
}
