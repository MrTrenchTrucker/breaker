package dev.breaker.dictation.commit.adapter

import android.content.Context
import android.os.Build
import android.os.Looper
import dev.breaker.dictation.commit.CommitService
import dev.breaker.dictation.commit.MainThread

/**
 * The one place the app builds a [CommitService] for the real device.
 *
 * The app wiring calls [create] once per process. The service shares its
 * focused-field registry with the text-insert mechanism through
 * [FocusedFieldHolder] (ADR-022), so a field published by that mechanism is the
 * field the commit finds.
 *
 * [takeStartCapture] builds the take-start capture over the same focused field
 * and the same main thread, so a take reads its window through the one hopper
 * this object owns.
 */
object CommitServices {

    /** The one hopper, built once per process and shared by the commit and the capture. */
    private val mainThread: MainThread by lazy {
        HandlerMainThread(Looper.getMainLooper(), HandlerMainThread.HOP_TIMEOUT_MILLIS)
    }

    /** Build the commit service on the application context, never on an activity. */
    fun create(context: Context): CommitService {
        val appContext: Context = context.applicationContext
        return CommitService(
            FocusedFieldHolder.registry,
            AndroidClipboardWriter(appContext),
            ToastNotice(appContext),
            mainThread,
            Build.VERSION.SDK_INT,
        )
    }

    /** The take-start capture over the same focused field and the same hopper as the commit. */
    fun takeStartCapture(): TakeStartCapture =
        TakeStartCapture(FocusedFieldHolder.registry, mainThread)
}
