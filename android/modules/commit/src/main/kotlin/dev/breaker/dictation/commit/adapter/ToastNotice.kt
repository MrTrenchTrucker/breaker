package dev.breaker.dictation.commit.adapter

import android.content.Context
import android.widget.Toast
import dev.breaker.dictation.commit.CommitTexts
import dev.breaker.dictation.commit.UserNotice

/**
 * Tells the user the text went to the clipboard.
 *
 * Shown only on the main thread: the commit service calls it inside its
 * main-thread hop, and a toast cannot be shown from any other thread.
 */
internal class ToastNotice(private val context: Context) : UserNotice {

    override fun showCopied() {
        Toast.makeText(context, CommitTexts.COPIED_TOAST, Toast.LENGTH_SHORT).show()
    }
}
