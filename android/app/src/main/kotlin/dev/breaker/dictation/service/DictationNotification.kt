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
        builder(context) { text }

    /** The shared body of [build] and [withText]: channel first, then the one action, then the return built around the given text. */
    private fun builder(context: Context, textLine: () -> String): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.dictation_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val icon = android.R.drawable.ic_btn_speak_now
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
        val openHistory = PendingIntent.getActivity(
            context,
            OPEN_HISTORY_REQUEST,
            Intent(context, SettingsLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(NotificationRoute.EXTRA_ROUTE, NotificationRoute.ROUTE_HISTORY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(context.getString(R.string.dictation_notification_title))
            .setContentText(textLine())
            .setContentIntent(openHistory)
            .setOngoing(true)
            .addAction(action)
            .build()
    }
}
