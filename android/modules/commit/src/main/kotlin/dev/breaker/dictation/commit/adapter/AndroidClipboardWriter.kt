package dev.breaker.dictation.commit.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import dev.breaker.dictation.commit.ClipboardWriter
import dev.breaker.dictation.commit.CommitTexts

/**
 * Writes text to the system clipboard.
 *
 * A device with no clipboard service answers false instead of throwing, so the
 * commit service can report that the text could not be put anywhere.
 */
internal class AndroidClipboardWriter(context: Context) : ClipboardWriter {

    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    override fun copy(text: String, sensitive: Boolean): Boolean {
        val clipboard: ClipboardManager = manager ?: return false
        val clip: ClipData = ClipData.newPlainText(CommitTexts.CLIP_LABEL, text)
        if (sensitive) {
            // The platform asks for this flag on clips that hold private text, so
            // the preview that Android 13 and later show after a copy hides
            // them. The key is written as a string because the named constant
            // does not exist on earlier releases; the string is harmless there.
            val extras = PersistableBundle()
            extras.putBoolean(IS_SENSITIVE_KEY, true)
            clip.description.setExtras(extras)
        }
        clipboard.setPrimaryClip(clip)
        return true
    }

    private companion object {
        const val IS_SENSITIVE_KEY: String = "android.content.extra.IS_SENSITIVE"
    }
}
