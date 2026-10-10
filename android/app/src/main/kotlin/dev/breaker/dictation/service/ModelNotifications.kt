package dev.breaker.dictation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.breaker.dictation.SettingsLauncherActivity
import dev.breaker.dictation.wiring.ModelDownloadNotice
import dev.breaker.dictation.wiring.ModelNotice
import dev.breaker.dictation.wiring.ModelSentences

/**
 * The notifications about the speech model, on a channel of their own that makes no sound.
 *
 * There are four: the model is missing (a tap opens the launcher on [NotificationRoute.ROUTE_MODEL]),
 * the model is downloading (an endless progress bar, because the download reports no progress), the
 * download is done or failed, and the tile could not be shown (a tap opens the launcher). The words are
 * constants, some in this file and the model ones in `ModelSentences`, in plain words for the user. The three model notices carry the title
 * "Speech model"; the tile one carries the name of the product, since it is not about the model.
 *
 * Every tap is one `PendingIntent.getActivity` made in [openLauncher], immutable, with a request code
 * of its own: two of them with the same code would be one pending intent, since extras do not count
 * when the platform compares intents. The codes and ids here differ from the ones of the ongoing
 * dictation notification, which this class never touches.
 *
 * When the user has switched notifications off nothing is shown and nothing fails; the call returns.
 */
internal class ModelNotifications(private val context: Context) : ModelNotice, ModelDownloadNotice {

    override fun showMissing() {
        show(MISSING_ID, MISSING_REQUEST, android.R.drawable.ic_btn_speak_now, MODEL_TITLE, ModelSentences.NO_MODEL, NotificationRoute.ROUTE_MODEL, false)
    }

    override fun showTileUnavailable() {
        show(UNAVAILABLE_ID, UNAVAILABLE_REQUEST, android.R.drawable.ic_btn_speak_now, TILE_TITLE, TILE_UNAVAILABLE, null, false)
    }

    override fun clear() {
        cancel(MISSING_ID)
        cancel(UNAVAILABLE_ID)
    }

    override fun downloading() {
        show(DOWNLOAD_ID, DOWNLOAD_REQUEST, android.R.drawable.stat_sys_download, MODEL_TITLE, ModelSentences.DOWNLOADING, null, true)
    }

    override fun done() {
        cancel(MISSING_ID)
        show(DOWNLOAD_ID, DOWNLOAD_REQUEST, android.R.drawable.stat_sys_download_done, MODEL_TITLE, MODEL_READY, null, false)
    }

    override fun failed(sentence: String) {
        show(DOWNLOAD_ID, DOWNLOAD_REQUEST, android.R.drawable.stat_notify_error, MODEL_TITLE, sentence, null, false)
    }

    private fun show(id: Int, request: Int, icon: Int, title: String, text: String, route: String?, working: Boolean) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openLauncher(request, route))
                .setOnlyAlertOnce(true)
            if (working) {
                notification.setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS).setProgress(0, 0, true)
            } else {
                notification.setAutoCancel(true)
            }
            manager.notify(id, notification.build())
        } catch (e: RuntimeException) {
            // The platform refused the notification; there is nothing else to show it with.
        }
    }

    private fun cancel(id: Int) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(id)
        } catch (e: RuntimeException) {
            // Nothing is left to take away.
        }
    }

    private fun openLauncher(request: Int, route: String?): PendingIntent {
        val intent = Intent(context, SettingsLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (route != null) intent.putExtra(NotificationRoute.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val CHANNEL_ID: String = "speech-model"
        const val CHANNEL_NAME: String = "Speech model"
        const val MODEL_TITLE: String = "Speech model"
        const val TILE_TITLE: String = "Breaker"
        const val MODEL_READY: String = "The speech model is ready. Tap the tile to start dictating."
        const val TILE_UNAVAILABLE: String = "The Breaker tile could not be shown. Tap to open Breaker."

        const val MISSING_ID: Int = 2
        const val UNAVAILABLE_ID: Int = 3
        const val DOWNLOAD_ID: Int = 4

        const val MISSING_REQUEST: Int = 2
        const val UNAVAILABLE_REQUEST: Int = 3
        const val DOWNLOAD_REQUEST: Int = 4
    }
}
