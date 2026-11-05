package dev.breaker.dictation.ui.render

/**
 * The save decision of one text box: the text goes out at most once, the first time the person
 * is done with it and it differs from the text the box started with. This is plain Kotlin with
 * no platform names, so a JVM test can load it.
 */
internal class EntryCommitRule(private val startText: String, private val send: (String) -> Unit) {

    private var sent = false

    /** Sends the box text once, when it differs from the start text. A later call does nothing. */
    fun finished(typed: String) {
        if (sent || typed == startText) return
        sent = true
        send(typed)
    }
}
