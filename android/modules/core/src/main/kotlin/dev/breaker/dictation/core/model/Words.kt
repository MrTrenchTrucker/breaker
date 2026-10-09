package dev.breaker.dictation.core.model

/**
 * One word the recogniser heard, and when it began.
 *
 * [startMs] is milliseconds from the start of the ACTIVE capture, counted from the
 * decoded sample count on the 16 kHz grid and rounded down (floor). It is never a
 * wall-clock time. The word source owns that origin and resets it when a capture
 * starts. A word has no null time: a word whose onset is unknown is not reported.
 *
 * [text] must not be blank. A negative [startMs] is refused.
 */
data class HeardWord(val text: String, val startMs: Long) {
    init {
        require(text.isNotBlank()) { "A heard word needs text that is not blank" }
        require(startMs >= 0) { "startMs must not be negative: $startMs" }
    }

    override fun toString(): String = "HeardWord(${text.length} chars, startMs=$startMs)"
}

/**
 * The recogniser's current hypothesis: the words heard since the last endpoint.
 *
 * [final] is true when the recogniser has closed that utterance. An empty [words]
 * list is a valid hypothesis. The list is copied when the update is built, so a
 * later change to the caller's list does not reach this value. The times in
 * [words] are as [HeardWord] describes: milliseconds from the start of the active
 * capture.
 */
@ConsistentCopyVisibility
data class WordUpdate private constructor(val words: List<HeardWord>, val final: Boolean) {
    override fun toString(): String = "WordUpdate(${words.size} words, final=$final)"

    companion object {
        /** Builds an update that holds a copy of [words]. */
        operator fun invoke(words: List<HeardWord>, final: Boolean): WordUpdate =
            WordUpdate(words.toList(), final)
    }
}
