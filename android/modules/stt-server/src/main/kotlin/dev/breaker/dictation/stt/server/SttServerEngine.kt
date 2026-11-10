package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.shared.api.JobStatus
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * The server path of speech-to-text. It sends one recording to Breaker's own job queue and waits
 * for the text.
 *
 * [transcribe] makes one POST that enqueues the job, then polls the job until it is done, has
 * failed, or the time runs out. One budget, measured from the start of the call, covers every
 * exchange, and each exchange runs under [CallWatchdog] so the caller gets control back at the
 * deadline. The POST is never repeated. Every path ends in one fixed sentence from
 * [FailureSentences]. The default limits are starting values; they were not measured on a device
 * or against a real server.
 *
 * The address and the key of a call are read once, together, from the target provider.
 */
class SttServerEngine internal constructor(
    private val target: () -> ServerTarget,
    private val limits: ServerLimits,
    private val http: ServerHttp,
    private val timing: Timing,
    private val boundary: BoundarySource,
) : SttEngine {

    /** The two-provider form with every part given. The key is read only for a usable address. */
    internal constructor(
        serverUrl: () -> String,
        apiKey: () -> String?,
        limits: ServerLimits,
        http: ServerHttp,
        timing: Timing,
        boundary: BoundarySource,
    ) : this(guardedTarget(serverUrl, apiKey), limits, http, timing, boundary)

    /** The engine for a real server: the platform transport, the system clock and random boundaries. */
    constructor(target: () -> ServerTarget, limits: ServerLimits = ServerLimits()) :
        this(target, limits, JdkServerHttp(), Timing.SYSTEM, BoundarySource.RANDOM)

    /** The engine for a real server from an address provider and a key provider. */
    constructor(serverUrl: () -> String, apiKey: () -> String?, limits: ServerLimits = ServerLimits()) :
        this(guardedTarget(serverUrl, apiKey), limits)

    private val watchdog = CallWatchdog(timing.deadline)

    /** Transcribes [request]. Returns a result on every path and never runs past its budget. */
    override fun transcribe(request: SttRequest): SttResult =
        try {
            runBlocking { attempt(request) }
        } catch (e: InterruptedException) {
            // Re-arms the caller's interrupt flag, which the wait cleared. It starts nothing and waits on nothing.
            Thread.currentThread().interrupt()
            SttResult.failure(SttError.OTHER, FailureSentences.INTERRUPTED)
        } catch (e: Exception) {
            SttResult.failure(SttError.OTHER, FailureSentences.UNEXPECTED)
        }

    private suspend fun attempt(request: SttRequest): SttResult {
        val startNanos = timing.clock.nowNanos()
        val budgetMs = limits.budgetFor(request.durationMs)
        fun remainingMs(): Long = budgetMs - (timing.clock.nowNanos() - startNanos) / NANOS_PER_MS

        val read = target()
        val base = read.base
        ServerEndpoint.addressProblem(base)?.let { return SttResult.failure(SttError.OTHER, it) }
        val key = read.key
        ServerEndpoint.keyProblem(key)?.let { return SttResult.failure(SttError.OTHER, it) }
        val secret = key ?: return SttResult.failure(SttError.OTHER, FailureSentences.NO_KEY)
        ServerEndpoint.fieldProblem(request.model, request.language)?.let { return SttResult.failure(SttError.OTHER, it) }
        val multipart = MultipartBodies.create(request.wavBytes, request.model, request.language, boundary.next())
        val auth = mapOf("Authorization" to "Bearer $secret", "Accept" to "application/json")

        val postUrl = ServerEndpoint.transcriptionsUrl(base)
        val postAbort = newAbort()
        val postLeft = remainingMs()
        val post = watchdog.run(minOf(limits.postCallCapMs, postLeft), postAbort) {
            val headers = auth + ("Content-Type" to multipart.contentType)
            http.exchange(
                ServerRequest(
                    "POST", postUrl, headers, multipart.bytes,
                    minOf(limits.connectTimeoutMs, postLeft).toInt(),
                    minOf(limits.postReplyTimeoutMs, postLeft).toInt(),
                ),
                postAbort,
            )
        }
        val posted = when (post) {
            is WatchdogOutcome.Finished -> post.value
            is WatchdogOutcome.Failed -> return ExchangeFailure.classify(post.error)
            WatchdogOutcome.Expired -> return SttResult.failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED)
        }
        when (posted.status) {
            202 -> Unit
            401, 403 -> return SttResult.failure(SttError.OTHER, FailureSentences.KEY_REFUSED)
            413 -> return SttResult.failure(SttError.OTHER, FailureSentences.TOO_LARGE)
            400 -> return SttResult.failure(SttError.OTHER, FailureSentences.NO_FILE)
            404 -> return SttResult.failure(SttError.SERVER_UNREACHABLE, FailureSentences.NO_ENDPOINT)
            429 -> return SttResult.failure(SttError.SERVER_UNREACHABLE, FailureSentences.NOT_TAKING_WORK, retryAfter(posted.headers))
            in 500..599 -> return SttResult.failure(SttError.SERVER_UNREACHABLE, FailureSentences.NOT_TAKING_WORK)
            else -> return SttResult.failure(SttError.OTHER, FailureSentences.UNEXPECTED_STATUS)
        }
        val accepted = try {
            ContractDecoder.jobAccepted(text(posted.body))
        } catch (e: ContractViolation) {
            return SttResult.failure(SttError.OTHER, FailureSentences.BAD_ANSWER)
        }
        if (!ServerEndpoint.isJobIdUsable(accepted.jobId)) {
            return SttResult.failure(SttError.OTHER, FailureSentences.BAD_ANSWER)
        }

        val jobUrl = ServerEndpoint.jobUrl(base, accepted.jobId)
        val schedule = PollSchedule(limits)
        var transientFailures = 0
        fun transientTolerated(): Boolean {
            transientFailures++
            return transientFailures <= limits.maxTransientPollFailures
        }
        for (index in 0 until limits.maxPolls) {
            val remaining = remainingMs()
            if (remaining <= 0) return SttResult.failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED)
            timing.sleeper.sleep(minOf(schedule.delayMs(index), remaining))
            if (remainingMs() <= 0) return SttResult.failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED)
            val pollLeft = remainingMs()
            val pollAbort = newAbort()
            val poll = watchdog.run(minOf(limits.pollCallCapMs, pollLeft), pollAbort) {
                http.exchange(
                    ServerRequest(
                        "GET", jobUrl, auth, null,
                        minOf(limits.connectTimeoutMs, pollLeft).toInt(),
                        minOf(limits.pollReadTimeoutMs, pollLeft).toInt(),
                    ),
                    pollAbort,
                )
            }
            val reply = when (poll) {
                is WatchdogOutcome.Finished -> poll.value
                is WatchdogOutcome.Failed -> {
                    if (!isTransient(poll.error) || !transientTolerated()) return ExchangeFailure.classify(poll.error)
                    continue
                }
                WatchdogOutcome.Expired -> {
                    if (!transientTolerated()) return SttResult.failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED)
                    continue
                }
            }
            when (reply.status) {
                200 -> {
                    val job = try {
                        ContractDecoder.job(text(reply.body))
                    } catch (e: ContractViolation) {
                        return SttResult.failure(SttError.OTHER, FailureSentences.BAD_ANSWER)
                    }
                    when (job.status) {
                        JobStatus.DONE -> {
                            val result = job.result ?: return SttResult.failure(SttError.OTHER, FailureSentences.BAD_ANSWER)
                            return try {
                                ContractDecoder.toSuccess(result)
                            } catch (e: ContractViolation) {
                                SttResult.failure(SttError.OTHER, FailureSentences.BAD_ANSWER)
                            }
                        }
                        JobStatus.FAILED -> return SttResult.failure(SttError.OTHER, FailureSentences.jobFailed(job.error, secret))
                        JobStatus.QUEUED, JobStatus.PROCESSING -> transientFailures = 0
                    }
                }
                401, 403 -> return SttResult.failure(SttError.OTHER, FailureSentences.KEY_REFUSED)
                404 -> return SttResult.failure(SttError.OTHER, FailureSentences.JOB_GONE)
                429, in 500..599 -> {
                    if (!transientTolerated()) {
                        return SttResult.failure(
                            SttError.SERVER_UNREACHABLE,
                            FailureSentences.NOT_TAKING_WORK,
                            if (reply.status == 429) retryAfter(reply.headers) else null,
                        )
                    }
                }
                else -> return SttResult.failure(SttError.OTHER, FailureSentences.UNEXPECTED_STATUS)
            }
        }
        return SttResult.failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED)
    }

    /** One abort handle per exchange. This is the only place a handle is created. */
    private fun newAbort(): AbortHandle = AbortHandle()

    /** A transport failure that counts toward the transient limit: an IO failure that is not a TLS failure. */
    private fun isTransient(e: Throwable): Boolean = e is IOException && !isTls(e)

    private fun isTls(e: Throwable): Boolean =
        ExchangeFailure.isTls(e)

    private fun text(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /** Parses a Retry-After header: seconds only, digits only (no sign, no decimals), bounded 1..3600.
     * Trim spaces/tabs around; several values for the header are ignored; anything else is ignored too.
     * Returns null when the header is missing, empty, non-digit, out of bounds, or repeated. */
    private fun retryAfter(headers: Map<String, List<String>>): Int? {
        val values = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value ?: return null
        if (values.size != 1) return null
        val text = values[0].trim()
        if (text.isEmpty()) return null
        if (!text.all { it in '0'..'9' }) return null
        val seconds = text.toIntOrNull() ?: return null
        return if (seconds in 1..3600) seconds else null
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
