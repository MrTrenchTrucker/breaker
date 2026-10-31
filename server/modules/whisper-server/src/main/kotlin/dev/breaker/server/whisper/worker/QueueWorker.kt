package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.jobs.JobPolicy
import dev.breaker.server.whisper.jobs.JobStoreOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.logging.Logger

/**
 * Runs the queued jobs strictly one at a time, in creation order. The next job is claimed
 * only after the previous one has returned, including all of its retries.
 *
 * Every store call is wrapped by [retryingStoreCall]: a store that throws is retried after
 * [JobPolicy.RETRY_DELAY], so a store outage stalls the worker instead of killing its loop,
 * and an interrupted job is never abandoned. A cancellation always breaks out (it is
 * rethrown before any retry), so a shutdown still leaves the job processing to be recovered.
 */
class QueueWorker internal constructor(
    private val store: JobStoreOps,
    private val forwarder: TranscriptionForwarder,
    private val pause: Pause = Pause.REAL,
) {
    // Conflated: a wake-up sent while the worker is busy is kept, so an enqueue that lands
    // between the last empty claim and the wait is never lost, and many wake-ups cost one.
    private val wakeUps: Channel<Unit> = Channel(Channel.CONFLATED)

    /** Takes the oldest queued job and carries it to done or failed; false when none was queued. */
    suspend fun runOnce(): Boolean {
        val claimed = retryingStoreCall("claimNext", null) { store.claimNext() } ?: return false
        val jobId: Long = claimed.job.id
        var attempt = claimed.job.attempts
        var isFirst = true
        while (true) {
            // The claim already counted the first attempt.
            if (!isFirst) {
                retryingStoreCall("beginRetryAttempt", jobId) { store.beginRetryAttempt(jobId) }
            }
            isFirst = false
            val outcome: ForwardOutcome = forwardOnce(ForwardRequest(jobId, claimed.audio, attempt))
            val failureText: String = when (outcome) {
                is ForwardOutcome.Success -> {
                    // Hold the result and retry only the write: a re-forward would run the service
                    // again, so the outcome is captured and complete() alone is retried until it lands.
                    retryingStoreCall("complete", jobId) { store.complete(jobId, outcome.result) }
                    return true
                }
                is ForwardOutcome.Failure -> failureTextOf(outcome)
            }
            if (attempt >= JobPolicy.MAX_ATTEMPTS) {
                retryingStoreCall("fail", jobId) {
                    store.fail(
                        jobId,
                        "failed after ${JobPolicy.MAX_ATTEMPTS} attempts: $failureText".take(JobPolicy.ERROR_TEXT_LIMIT),
                    )
                }
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
     * that a running worker still holds. Recovery is retried until it returns, so the worker
     * claims nothing while recovery is still fighting a store outage.
     */
    suspend fun run() {
        recoverInterrupted()
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

    /**
     * Calls one store seam until it returns or the coroutine is cancelled. A throw is logged
     * (job id and exception class only) and retried after [JobPolicy.RETRY_DELAY], with
     * unbounded attempts so a stuck call never strands the job. A [CancellationException] is a
     * shutdown and is always rethrown, so the loop ends and the job stays processing. The pause
     * is outside the try, so a cancel during the back-off breaks out too.
     */
    private suspend fun <T> retryingStoreCall(op: String, jobId: Long?, call: suspend () -> T): T {
        while (true) {
            try {
                return call()
            } catch (cancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw cancellation
            } catch (failure: Exception) {
                logStoreFailure(op, jobId, failure)
                pause.pause(JobPolicy.RETRY_DELAY)
            }
        }
    }

    private fun logStoreFailure(op: String, jobId: Long?, failure: Throwable) {
        val where = if (jobId == null) "store.$op failed" else "store.$op failed for job $jobId"
        // Only the class name is kept: a store exception message can carry a path or a key.
        val name = failure::class.simpleName ?: "Exception"
        logger.warning("$where ($name)")
    }

    private suspend fun recoverInterrupted() {
        retryingStoreCall("recoverInterrupted", null) { store.recoverInterrupted() }
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

    private val logger: Logger = Logger.getLogger("dev.breaker.server.whisper.worker.QueueWorker")

    private companion object {
        const val BLANK_REASON_TEXT: String = "the transcription service reported a failure"
    }
}
