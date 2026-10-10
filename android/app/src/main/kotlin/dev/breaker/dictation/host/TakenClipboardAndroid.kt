package dev.breaker.dictation.host

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import dev.breaker.dictation.wiring.TakenClipboard

/**
 * The Android half of the clipboard seam: copies a taken take's text to the phone's primary clip.
 * One platform call, on the main thread only; the caller guards the call, so a throw propagates up to
 * it rather than stopping the settle. The label here is the app's own name, not user text - the system
 * shows it only as the clip's invisible title, never as a sentence. Main thread only.
 */
class TakenClipboardAndroid(private val context: Context) : TakenClipboard {
    override fun copy(text: String) {
        val clipboard = context.getSystemService(
            android.content.Context.CLIPBOARD_SERVICE,
        ) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Breaker", text))
    }
}
