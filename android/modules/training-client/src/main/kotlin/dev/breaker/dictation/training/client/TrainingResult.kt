package dev.breaker.dictation.training.client

/**
 * The typed answer of one call on [TrainingServer].
 *
 * A closed set, so a `when` over it fails to compile when a case is added and
 * every caller has to be told what to do with the new case. Expected outcomes
 * (no session, no server, a refusal, a lost connection) are values here, never
 * exceptions.
 *
 * Status: shape only, not built into a running app.
 */
sealed interface TrainingResult<out T> {
    /** The call worked; [value] is what it returned. */
    data class Ok<T>(val value: T) : TrainingResult<T>

    /** The user has no session, so the call was not made or the server refused the missing token. */
    data object NotSignedIn : TrainingResult<Nothing>

    /** No training server is configured, so training is not offered. */
    data object NoServer : TrainingResult<Nothing>

    /** The server refused the call for any reason other than size. */
    data object Refused : TrainingResult<Nothing>

    /** The server refused a size: a sample or a model over its limit. */
    data object TooLarge : TrainingResult<Nothing>

    /** No reply arrived. The server may or may not have acted on the call. */
    data object Network : TrainingResult<Nothing>

    /** The server cannot serve this call now. */
    data object Unavailable : TrainingResult<Nothing>
}
