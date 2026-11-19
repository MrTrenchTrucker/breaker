package dev.breaker.dictation.stt.ondevice

/**
 * The answer of [KeywordChooser.choose]: the keyword load to use, and the reason a user model was ignored, or null
 * when no user model was ignored (a ready user model, or none installed).
 */
class KeywordChoice(val load: KeywordLoad, val userIgnored: UserKeywordReason?)

/**
 * Chooses the keyword model for a take: the user model when it is ready, else the default load.
 *
 * [user] is asked first. A ready user model is the answer and [default] is not asked. An absent user model asks
 * [default]. An ignored user model asks [default] and reports the typed reason; the user model is never used.
 * The chooser catches nothing: [user] is a pure lambda that does not throw (the user loader never throws), and
 * an exception from either lambda reaches the caller.
 */
class KeywordChooser(
    private val user: () -> UserKeywordLoad,
    private val default: () -> KeywordLoad,
) {

    /** Answers the keyword load for this take. */
    fun choose(): KeywordChoice = when (val loaded = user()) {
        is UserKeywordLoad.Ready -> KeywordChoice(KeywordLoad.Ready(loaded.model), null)
        UserKeywordLoad.Absent -> KeywordChoice(default(), null)
        is UserKeywordLoad.Ignored -> KeywordChoice(default(), loaded.reason)
    }
}
