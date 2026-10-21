package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttResult
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Turns an exception from one exchange with the server into the failure the engine returns.
 * This is the one place that classifies transport errors, and every detail it gives is a fixed
 * sentence, so an exception message never reaches the user. A TLS problem is never retried:
 * it is reported at once. Any exception that is not a transport error is reported as unexpected.
 */
internal object ExchangeFailure {

    /** Classifies [e] by the table of the engine: size cap, connect, read timeout, TLS, other IO. */
    fun classify(e: Throwable): SttResult = when (e) {
        is ReplyTooLargeException -> SttResult.failure(SttError.OTHER, FailureSentences.BAD_ANSWER)
        is ConnectFailedException -> connectFailure(e)
        is SocketTimeoutException -> SttResult.failure(SttError.TIMEOUT, FailureSentences.NO_ANSWER_IN_TIME)
        is SSLException -> SttResult.failure(SttError.SERVER_UNREACHABLE, FailureSentences.TLS_FAILED)
        is IOException -> if (isTls(e)) {
            SttResult.failure(SttError.SERVER_UNREACHABLE, FailureSentences.TLS_FAILED)
        } else {
            SttResult.failure(SttError.SERVER_UNREACHABLE, FailureSentences.CONNECTION_LOST)
        }
        else -> SttResult.failure(SttError.OTHER, FailureSentences.UNEXPECTED)
    }

    private fun connectFailure(e: ConnectFailedException): SttResult =
        if (isTls(e)) {
            SttResult.failure(SttError.SERVER_UNREACHABLE, FailureSentences.TLS_FAILED)
        } else {
            SttResult.failure(SttError.SERVER_UNREACHABLE, FailureSentences.UNREACHABLE)
        }

    /** How many exceptions of a cause chain are looked at, so a cycle in the chain cannot loop. */
    private const val MAX_CHAIN = 8

    /**
     * True when [e] or a cause below it, within [MAX_CHAIN] exceptions, is an SSLException.
     * A copy of an exception may carry the original as its cause, so the whole chain is looked at.
     */
    internal fun isTls(e: Throwable): Boolean {
        var current: Throwable? = e
        var seen = 0
        while (current != null && seen < MAX_CHAIN) {
            if (current is SSLException) return true
            current = current.cause
            seen++
        }
        return false
    }
}
