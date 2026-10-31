package dev.breaker.server.whisper.jobs

import java.time.Instant

/** Metadata only: never the audio, never the result. */
internal class Job(
    val id: Long,
    val ownerAccountId: Long,
    val status: JobStatus,
    val attempts: Int,
    val error: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

internal class ClaimedJob(val job: Job, val audio: ByteArray) {
    // Logs and failure messages must never carry the audio: only the id.
    override fun toString(): String = "ClaimedJob(id=${job.id})"
}

internal sealed class JobPoll {
    // No such job and a job of another owner give this one answer, so a caller
    // cannot probe which ids exist.
    object NotFound : JobPoll()

    class Pending(val status: JobStatus) : JobPoll()

    class Failed(val error: String) : JobPoll()

    // Only the first fetch gets this; the stored result has just been deleted.
    class Result(val text: String) : JobPoll() {
        override fun toString(): String = "JobPoll.Result(redacted)"
    }

    // Done, but the result was already fetched or has expired.
    object ResultGone : JobPoll()
}

internal class IllegalJobTransitionException(message: String) : IllegalStateException(message)
