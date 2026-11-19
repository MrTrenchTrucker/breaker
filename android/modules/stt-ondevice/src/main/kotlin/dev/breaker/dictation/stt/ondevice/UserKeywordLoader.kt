package dev.breaker.dictation.stt.ondevice

/** The outcome of [UserKeywordLoader.load]. */
sealed class UserKeywordLoad {

    /** The user model checks out. Its model has the keyword id and [KeywordSource.USER]. */
    data class Ready(val model: KeywordModel) : UserKeywordLoad()

    /** No user model is installed. */
    object Absent : UserKeywordLoad()

    /** A user model is installed but a check failed; [reason] says which. No model is built from it. */
    data class Ignored(val reason: UserKeywordReason) : UserKeywordLoad()
}

/**
 * Loads the user keyword model from the folder of [store] when it checks out.
 *
 * It judges the folder with [UserKeywordCheck] and never changes it, and it never hashes a file: a load reads the
 * stamp, the sizes and the modified times and, once the stamp matches, tokens.txt once (a few KB, capped at the
 * factory's limit). A model that fails any check is [UserKeywordLoad.Ignored]
 * with its typed reason, so it is never half-used and the default is used for that take. [load] does not throw.
 */
class UserKeywordLoader(
    private val store: UserKeywordStore,
    private val phrases: List<KeywordLine> = FixedPhrases.all(),
) {

    /** Judges the user folder now and answers the load for this call. */
    fun load(): UserKeywordLoad {
        val directory = store.directory()
        return when (val verdict = UserKeywordCheck.verdict(directory, phrases)) {
            is UserKeywordVerdict.Ready ->
                UserKeywordLoad.Ready(KeywordModel(KEYWORD_MODEL_ID, directory, verdict.digest, KeywordSource.USER))
            UserKeywordVerdict.Absent -> UserKeywordLoad.Absent
            is UserKeywordVerdict.Bad -> UserKeywordLoad.Ignored(verdict.reason)
        }
    }
}
