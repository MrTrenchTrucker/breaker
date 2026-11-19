package dev.breaker.dictation.training.client

/**
 * Why a call on the training server did not work, as a code. One value for each failed case of
 * [TrainingResult]; no sentence is carried, so the caller decides what to show.
 *
 * Status: written without a compiler, not built, not tried on a phone.
 */
enum class TrainingFailure { NOT_SIGNED_IN, NO_SERVER, REFUSED, TOO_LARGE, NETWORK, UNAVAILABLE }

/**
 * Takes a result apart: [onOk] gets the value of a worked call, [onFailed] gets the [TrainingFailure] of
 * every other case. The `when` has no else branch, so a new case in [TrainingResult] stops this file compiling.
 */
internal inline fun <T, R> TrainingResult<T>.fold(onOk: (T) -> R, onFailed: (TrainingFailure) -> R): R =
    when (this) {
        is TrainingResult.Ok -> onOk(value)
        TrainingResult.NotSignedIn -> onFailed(TrainingFailure.NOT_SIGNED_IN)
        TrainingResult.NoServer -> onFailed(TrainingFailure.NO_SERVER)
        TrainingResult.Refused -> onFailed(TrainingFailure.REFUSED)
        TrainingResult.TooLarge -> onFailed(TrainingFailure.TOO_LARGE)
        TrainingResult.Network -> onFailed(TrainingFailure.NETWORK)
        TrainingResult.Unavailable -> onFailed(TrainingFailure.UNAVAILABLE)
    }

/** The failure of a failed result, or null when the call worked. */
internal fun TrainingResult<*>.failureOrNull(): TrainingFailure? =
    fold<Any?, TrainingFailure?>(onOk = { null }, onFailed = { failure -> failure })
