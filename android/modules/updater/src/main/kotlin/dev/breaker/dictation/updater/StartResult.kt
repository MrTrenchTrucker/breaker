package dev.breaker.dictation.updater

/**
 * The result of a call on the first-start surface: what the surface shows, and the sentence it shows when it shows one.
 *
 * The sentence is present exactly when [shown] is [Shown.SENTENCE], and a sentence must not be blank.
 */
public class StartResult internal constructor(
    /** What the surface shows. */
    public val shown: Shown,
    /** The sentence to show; null unless [shown] is [Shown.SENTENCE]. */
    public val sentence: String?,
) {
    init {
        require((shown == Shown.SENTENCE) == (sentence != null)) { "a sentence is present exactly when shown is SENTENCE" }
        require(sentence == null || sentence.isNotBlank()) { "a sentence must have text" }
    }
}
