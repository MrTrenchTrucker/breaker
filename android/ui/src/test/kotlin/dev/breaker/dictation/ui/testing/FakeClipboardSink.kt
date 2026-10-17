package dev.breaker.dictation.ui.testing

import dev.breaker.dictation.ui.screen.history.ClipboardSink

/**
 * A [ClipboardSink] held in memory. [calls] holds every text it was asked to copy, in order,
 * failed or not, and [answer] is what a call that does not throw returns. While [fail] is set
 * the call throws, and the message holds both [HISTORY_FAILURE_MARKER] and the text it was
 * given, so a test can check that neither reaches a notice.
 */
internal class FakeClipboardSink : ClipboardSink {
    val calls: MutableList<String> = mutableListOf()
    var answer: Boolean = true
    var fail: Boolean = false

    override fun copy(text: String): Boolean {
        calls.add(text)
        if (fail) throw IllegalStateException("copy failed: $text $HISTORY_FAILURE_MARKER")
        return answer
    }
}
