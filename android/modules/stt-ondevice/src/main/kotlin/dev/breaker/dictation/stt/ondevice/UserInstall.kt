package dev.breaker.dictation.stt.ondevice

/**
 * The result of one install of the user keyword model.
 *
 * [Installed] means the model with [Installed.digest] is the installed one now; [Installed.unchanged] is true when it
 * already was and nothing was written. [Refused] means the archive was not accepted. [Failed] means the install could
 * not finish. In both refusal cases the previous model, if there is one, is untouched or put back. No result carries a
 * path or an exception.
 */
sealed class UserInstall {
    /** The model with [digest] is installed; [unchanged] is true when the same model was already installed. */
    data class Installed(val digest: String, val unchanged: Boolean) : UserInstall()

    /** The archive was not accepted; [reason] says which check refused it. */
    data class Refused(val reason: UserKeywordReason) : UserInstall()

    /** The install could not finish; [reason] says which kind of step stopped it. */
    data class Failed(val reason: UserKeywordReason) : UserInstall()
}
