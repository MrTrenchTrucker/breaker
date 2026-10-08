package dev.breaker.dictation.ui.render

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import dev.breaker.dictation.ui.screen.history.ClipboardSink

/*
 * The system clipboard, as the history screen sees it.
 *
 * The screen hands over one piece of text at a time and learns only whether it went
 * onto the clipboard. Nothing is kept here and nothing is logged: the text lives in
 * the clip for as long as the system keeps that clip, and never in this class.
 */

/**
 * Puts text on the system clipboard and marks it as private.
 *
 * The mark asks the system to hide the text in the copy preview it shows on Android
 * 13 and later. A device with no clipboard service, or one that refuses the write,
 * answers false. The text is never part of anything thrown or written here: a
 * failure is reported as the answer alone.
 *
 * @param context the context the view is built with, normally an activity's.
 */
internal class AndroidClipboard(context: Context) : ClipboardSink {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    override fun copy(text: String): Boolean {
        val clipboard = manager ?: return false
        return try {
            val clip = ClipData.newPlainText(NO_LABEL, text)
            val extras = PersistableBundle()
            extras.putBoolean(IS_SENSITIVE_KEY, true)
            clip.description.setExtras(extras)
            clipboard.setPrimaryClip(clip)
            true
        } catch (failure: RuntimeException) {
            false
        }
    }

    private companion object {
        /** The key the platform reads to hide a clip in its preview. A string, because the named constant is newer than some releases. */
        const val IS_SENSITIVE_KEY: String = "android.content.extra.IS_SENSITIVE"

        /** An empty label, so no words of this module are shown by the system. */
        const val NO_LABEL: String = ""
    }
}
