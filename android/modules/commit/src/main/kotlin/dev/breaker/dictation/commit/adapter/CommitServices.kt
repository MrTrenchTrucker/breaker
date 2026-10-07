package dev.breaker.dictation.commit.adapter

import android.content.Context
import android.os.Build
import android.os.Looper
import dev.breaker.dictation.commit.CommitService

/**
 * The one place the app builds a [CommitService] for the real device.
 *
 * The app wiring calls [create] once per process. The service shares its
 * focused-field registry with the text-insert mechanism through
 * [FocusedFieldHolder] (ADR-022), so a field published by that mechanism is the
 * field the commit finds.
 */
object CommitServices {

    /** Build the commit service on the application context, never on an activity. */
    fun create(context: Context): CommitService {
        val appContext: Context = context.applicationContext
        return CommitService(
            FocusedFieldHolder.registry,
            AndroidClipboardWriter(appContext),
            ToastNotice(appContext),
            HandlerMainThread(Looper.getMainLooper(), HandlerMainThread.HOP_TIMEOUT_MILLIS),
            Build.VERSION.SDK_INT,
        )
    }
}
