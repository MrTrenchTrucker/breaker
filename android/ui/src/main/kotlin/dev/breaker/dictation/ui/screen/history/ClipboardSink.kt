package dev.breaker.dictation.ui.screen.history

/*
 * The one way the history screen puts text on the phone's clipboard.
 *
 * The real sink is the phone's clipboard service, supplied by the view. The model
 * only sees this seam, so its tests run on the plain JVM.
 */

/** Puts [text] on the clipboard. Returns true when the clipboard took it. */
internal fun interface ClipboardSink {
    fun copy(text: String): Boolean
}
