package dev.breaker.server.whisper.jobs

/**
 * The five store operations the queue worker calls. It is the seam the worker depends on
 * instead of the concrete [JobStore], so the worker can be tested against a store that fails
 * and so a store outage is retried by the worker rather than fatal to its loop.
 *
 * Each method is one of the worker's store seams and may throw while the database is
 * unavailable; the worker wraps every call and retries it. The other [JobStore] operations
 * (enqueue, fetch, purgeExpired, get) are served to the job API, not the worker, and are not
 * part of this interface.
 */
internal interface JobStoreOps {
    /** Takes the oldest queued job and marks it processing; null when none was queued. */
    suspend fun claimNext(): ClaimedJob?

    /** Counts one more attempt for a processing job before its next forward; returns the attempt number. */
    suspend fun beginRetryAttempt(jobId: Long): Int

    /** Records a processing job as done, with the result the service returned for it. */
    suspend fun complete(jobId: Long, result: String)

    /** Records a processing job as failed, with the error text of its last attempt. */
    suspend fun fail(jobId: Long, error: String)

    /** At start: fails the exhausted and requeues the interrupted jobs a previous process left processing. */
    suspend fun recoverInterrupted(): Int
}
