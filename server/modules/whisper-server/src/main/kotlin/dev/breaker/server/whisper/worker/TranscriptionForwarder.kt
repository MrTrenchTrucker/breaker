package dev.breaker.server.whisper.worker

/**
 * The seam to the configured transcription service. The worker calls it for one job at a
 * time, so an implementation never has to expect two calls in parallel from one worker.
 */
fun interface TranscriptionForwarder {
    suspend fun forward(request: ForwardRequest): ForwardOutcome
}

/** One call to the transcription service; [attempt] counts from 1. */
class ForwardRequest(val jobId: Long, val audio: ByteArray, val attempt: Int) {
    // The audio must never reach a log line or a failure message through string conversion.
    override fun toString(): String = "ForwardRequest(jobId=$jobId, attempt=$attempt)"
}

sealed class ForwardOutcome {
    /** [result] is the service's reply as opaque text; an empty reply is a failure, not a success. */
    class Success(val result: String) : ForwardOutcome() {
        init {
            require(result.isNotBlank()) { "whisper-server: the transcription result is blank" }
        }

        // The result is the caller's private text and must not leak through string conversion.
        override fun toString(): String = "ForwardOutcome.Success(redacted)"
    }

    /** [reason] is stored and shown to the caller as given, so it must already be safe text. */
    class Failure(val reason: String) : ForwardOutcome()
}
