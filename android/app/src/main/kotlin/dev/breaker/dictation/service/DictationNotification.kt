package dev.breaker.dictation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.breaker.dictation.R
import dev.breaker.dictation.SettingsLauncherActivity

/**
 * The ongoing notification of the microphone service: a title, a line of text, one action that
 * switches dictation off, and a tap that opens the launcher activity on the history route
 * ([NotificationRoute]). All words come from the app's string resources and nothing else is shown.
 *
 * A refused foreground leaves a plain notice instead ([refusal]): the same title, the same icon, the
 * same tap and the same id, with the refusal sentence as its one text line, and no action and no ongoing
 * flag. It is posted by [postRefusal] only when the user permits notifications ([notificationsPermitted]).
 */
internal object DictationNotification {
    const val NOTIFICATION_ID: Int = 1
    private const val CHANNEL_ID: String = "dictation"
    private const val SWITCH_OFF_REQUEST: Int = 0
    private const val OPEN_HISTORY_REQUEST: Int = 1

    /** Makes sure the channel exists (creating it again is harmless) and builds the notification. */
    fun build(context: Context): Notification =
        withText(context, context.getString(R.string.dictation_notification_text))

    /** The ongoing notification with a line of [text] in place of the plain line: the same id, the same channel, the same icon, the same one action, the same tap that opens the history route. Only the text under the title is not the string resource. */
    fun withText(context: Context, text: String): Notification =
        builder(context, armed = true) { text }

    /** The plain notice a refused foreground leaves: the app title, [text] as the one line, the same icon and tap, no action, not ongoing. */
    fun refusal(context: Context, text: String): Notification =
        builder(context, armed = false) { text }

    /** Posts the refusal notice under the id of the ongoing notification. The caller checks that notifications are permitted. */
    fun postRefusal(context: Context, sentence: String) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, refusal(context, sentence))
    }

    /** Whether the user lets Breaker post notifications. */
    fun notificationsPermitted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /**
     * The shared body of every notice. [armed] is the ongoing notice of the service: it is ongoing and adds
     * the one switch-off action. A plain notice (not [armed]) has neither; it keeps the title, the icon, the
     * one text line and the tap. Channel first, then the notice, then the action when it is armed.
     */
    private fun builder(context: Context, armed: Boolean, textLine: () -> String): Notification {
        ensureChannel(context)
        val icon = R.drawable.ic_notification_mic
        val notice = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(context.getString(R.string.dictation_notification_title))
            .setContentText(textLine())
            .setContentIntent(openHistoryTap(context))
        if (armed) {
            val switchOff = PendingIntent.getService(
                context,
                SWITCH_OFF_REQUEST,
                Intent(context, DictationForegroundService::class.java).setAction(ACTION_DISARM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val action = Notification.Action.Builder(
                Icon.createWithResource(context, icon),
                context.getString(R.string.dictation_action_off),
                switchOff,
            ).build()
            notice.setOngoing(true).addAction(action)
        }
        return notice.build()
    }

    /** Creates the channel the notices are posted on. Creating it again is harmless. */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.dictation_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** The tap of every notice: the launcher activity on the history route, immutable. */
    private fun openHistoryTap(context: Context): PendingIntent {
        val openHistory = PendingIntent.getActivity(
            context,
            OPEN_HISTORY_REQUEST,
            Intent(context, SettingsLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(NotificationRoute.EXTRA_ROUTE, NotificationRoute.ROUTE_HISTORY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return openHistory
    }
}
