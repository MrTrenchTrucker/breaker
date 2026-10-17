package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.jobs.JobPolicy
import dev.breaker.server.whisper.jobs.JobStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Runs the queued jobs strictly one at a time, in creation order. The next job is claimed
 * only after the previous one has returned, including all of its retries.
 */
class QueueWorker internal constructor(
    private val store: JobStore,
    private val forwarder: TranscriptionForwarder,
    private val pause: Pause = Pause.REAL,
) {
    // Conflated: a wake-up sent while the worker is busy is kept, so an enqueue that lands
    // between the last empty claim and the wait is never lost, and many wake-ups cost one.
    private val wakeUps: Channel<Unit> = Channel(Channel.CONFLATED)

    /** Takes the oldest queued job and carries it to done or failed; false when none was queued. */
    suspend fun runOnce(): Boolean {
        val claimed = store.claimNext() ?: return false
        val jobId: Long = claimed.job.id
        var attempt = claimed.job.attempts
        var isFirst = true
        while (true) {
            // The claim already counted the first attempt.
            if (!isFirst) {
                store.beginRetryAttempt(jobId)
            }
            isFirst = false
            val outcome: ForwardOutcome = forwardOnce(ForwardRequest(jobId, claimed.audio, attempt))
            val failureText: String = when (outcome) {
                is ForwardOutcome.Success -> {
                    store.complete(jobId, outcome.result)
                    return true
                }
                is ForwardOutcome.Failure -> failureTextOf(outcome)
            }
            if (attempt >= JobPolicy.MAX_ATTEMPTS) {
                store.fail(jobId, "failed after ${JobPolicy.MAX_ATTEMPTS} attempts: $failureText".take(JobPolicy.ERROR_TEXT_LIMIT))
                return true
            }
            // The job stays processing during the wait, so the next job cannot start.
            pause.pause(JobPolicy.RETRY_DELAY)
            attempt += 1
        }
    }

    /**
     * Recovers jobs a previous process left processing, then works until cancelled. Call it
     * from the one worker of a process, at start: a second recovery would requeue a job
     * that a running worker still holds.
     */
    suspend fun run() {
        store.recoverInterrupted()
        while (true) {
            while (runOnce()) {
                // keep draining the queue
            }
            wakeUps.receive()
        }
    }

    /** Tells a waiting [run] that a job was enqueued. Safe from any thread and never blocks. */
    fun wake() {
        wakeUps.trySend(Unit)
    }

    private suspend fun forwardOnce(request: ForwardRequest): ForwardOutcome {
        try {
            return forwarder.forward(request)
        } catch (failure: Exception) {
            if (failure is CancellationException) {
                // A real cancel is a shutdown, not a failed job: ensureActive rethrows it, the job
                // stays processing and is recovered at the next start. A cancellation that came
                // from inside the forwarder while this coroutine is still active (for example a
                // timeout the forwarder set on its own call) is just a failed attempt.
                currentCoroutineContext().ensureActive()
            }
            // Only the class name is kept: the message of a client exception can hold a URL or a key.
            val name: String = failure::class.simpleName ?: "Exception"
            return ForwardOutcome.Failure("the transcription service call failed ($name)")
        }
    }

    private fun failureTextOf(failure: ForwardOutcome.Failure): String {
        if (failure.reason.isBlank()) {
            return BLANK_REASON_TEXT
        }
        return failure.reason.take(JobPolicy.ERROR_TEXT_LIMIT)
    }

    private companion object {
        const val BLANK_REASON_TEXT: String = "the transcription service reported a failure"
    }
}
