package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared tap of the two notices, held by the rules of [NotificationContentGateTest]. The ongoing notice and
 * the refusal notice may both take their content intent from the one tap function, and a second content intent
 * that is the switch off, or a call to a helper that is not the tap, must be reported.
 */
internal class NotificationTapGateTest {

    private val content = NotificationContentGateTest()

    /** The sample with [old] replaced by [new]; fails by name when the text is not in it. */
    private fun edited(old: String, new: String): String {
        check(content.sample.contains(old)) { "app: a tap sample lost its text '$old'" }
        return content.sample.replace(old, new)
    }

    /** The same notification with the tap moved into the shared function, which both notices call. */
    private val helperSample = """
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
                return Notification.Builder(context, CHANNEL_ID)
                    .setContentIntent(openHistoryTap(context))
                    .setOngoing(true)
                    .addAction(action)
                    .build()
            }
            private fun openHistoryTap(context: Context): PendingIntent {
                return PendingIntent.getActivity(
                    context,
                    1,
                    Intent(context, SettingsLauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(NotificationRoute.EXTRA_ROUTE, NotificationRoute.ROUTE_HISTORY),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        }
    """.trimIndent()

    @Test
    fun `the content intent may be the one shared tap function that both notices use`() {
        assertEquals("app: the shared tap function was reported", emptyList<String>(), content.problems(helperSample))
    }

    @Test
    fun `a content intent that is an unknown helper or an undefined shared tap is reported`() {
        assertEquals(listOf("CONTENT_INTENT"), content.problems(edited("setContentIntent(openHistory)", "setContentIntent(openTap(context))")))
        assertEquals(listOf("CONTENT_INTENT"), content.problems(edited("setContentIntent(openHistory)", "setContentIntent(openHistoryTap(context))")))
    }

    @Test
    fun `a second content intent that is the switch off is reported`() {
        assertEquals(listOf("CONTENT_INTENT"), content.problems(edited(".addAction(action)", ".setContentIntent(switchOff).addAction(action)")))
    }
}
