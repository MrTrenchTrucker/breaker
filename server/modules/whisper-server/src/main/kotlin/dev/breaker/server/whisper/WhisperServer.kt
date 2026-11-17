package dev.breaker.server.whisper

import dev.breaker.server.whisper.config.WhisperConfig
import dev.breaker.server.whisper.db.SqliteDatabase
import dev.breaker.server.whisper.http.HttpTokenCheck
import dev.breaker.server.whisper.http.AgentRateLimit
import dev.breaker.server.whisper.http.UserRateLimit
import dev.breaker.server.whisper.http.TranscriptionApi
import dev.breaker.server.whisper.http.WhisperEngine
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.worker.DownstreamConfig
import dev.breaker.server.whisper.worker.HttpTranscriptionForwarder
import dev.breaker.server.whisper.worker.QueueWorker
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Testable shutdown logic for the whisper-server.
 *
 * Stop the server, cancel the worker, and join it within a bound. When the join
 * completes, recover interrupted jobs and close the database. When the join times
 * out (worker stuck), swallow the timeout silently and touch nothing: no recover,
 * no close. The job stays PROCESSING and is requeued on the next start.
 */
internal class WhisperShutdown(
    private val stopServer: () -> Unit,
    private val workerJob: Job,
    private val store: JobStore,
    private val db: SqliteDatabase,
    private val joinTimeoutMs: Long = 5000,
) {
    suspend fun run() {
        stopServer()
        workerJob.cancel()
        try {
            withTimeout(joinTimeoutMs) {
                workerJob.join()
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            return
        }
        store.recoverInterrupted()
        db.close()
    }
}

/** Renders a running time as HhMmSs: Duration.ofSeconds(3723) -> "1h2m3s". */
internal fun formatUptime(duration: Duration): String {
    val totalSeconds = duration.seconds
    return "${totalSeconds / 3600}h${totalSeconds % 3600 / 60}m${totalSeconds % 60}s"
}

/** A fresh uptime string on each call: the elapsed from the start mark captured once at startup to
 *  the monotonic source's current nanos. The source is monotonic (System::nanoTime in production),
 *  so the rendered uptime never goes backwards even if a wall clock used elsewhere is stepped back. */
internal fun uptimeProvider(monotonicNanos: () -> Long): () -> String {
    val start = monotonicNanos()
    return { formatUptime(Duration.ofNanos(monotonicNanos() - start)) }
}

/**
 * The ONE startup-computed value the /health route still needs: a provider that renders the uptime
 * on demand from a monotonic source. The provider - not a captured string - is what lets uptime
 * advance as the source moves between requests. The forwarding target is no longer part of
 * startup state at all: nothing here reads the configuration, and the forwarder URL reaches the
 * worker directly.
 */
internal fun startupUptime(monotonicNanos: () -> Long): () -> String =
    uptimeProvider(monotonicNanos)

/**
 * Wires the runnable TranscriptionApi from the opened store, the worker and the configuration. It is
 * the one place both main() and a test build the api, so a mutant on the health wiring cannot hide.
 * Uptime is driven by [monotonicNanos] (System::nanoTime in production), never a wall clock.
 */
internal fun buildTranscriptionApi(
    store: JobStore,
    wakeWorker: () -> Unit,
    config: WhisperConfig,
    monotonicNanos: () -> Long,
): TranscriptionApi {
    val uptime = startupUptime(monotonicNanos)
    // The token check is the one place this module talks to the token service: the enqueue
    // moves a live session token's expiry (use), the poll uses the read-only check (peek).
    val tokenCheck = HttpTokenCheck(config.syncApiBaseUrl, config.syncApiServiceKey)
    // The optional per-agent-token rate limit: off when BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN
    // is unset (agentRateLimitPerMin == null), otherwise a fixed 60 s window per agent token.
    val rateLimit = config.agentRateLimitPerMin?.let { cap ->
        AgentRateLimit(cap, Clock.systemUTC())
    }
    // The optional per-account rate limit: off when BREAKER_WHISPER_USER_RATE_LIMIT_PER_MIN is unset
    // (userRateLimitPerMin == null), otherwise a fixed 60 s window per account, applied to session
    // and agent tokens alike.
    val userRateLimit = config.userRateLimitPerMin?.let { cap ->
        UserRateLimit(cap, Clock.systemUTC())
    }
    return TranscriptionApi(
        store,
        wakeWorker,
        uptime = uptime,
        tokenCheck = tokenCheck,
        limit = rateLimit,
        userLimit = userRateLimit,
    )
}

/**
 * Wires the [JobStore] from the opened database and the configuration. It is the one place both
 * main() and a test build the store, so a mutant that drops the configured result TTL cannot hide:
 * [config.resultTtlSeconds] is threaded into the store as milliseconds, not the 600 s default. The
 * [clock] is injected for testability, same shape as buildTranscriptionApi's monotonic source.
 */
internal fun buildJobStore(
    db: SqliteDatabase,
    config: WhisperConfig,
    clock: Clock = Clock.systemUTC(),
): JobStore = JobStore(db, clock, resultTtlMs = config.resultTtlSeconds * 1000L)

/** Blocks the calling thread until the worker job is cancelled, so main does not return while the service is up. */
internal fun blockUntilShutdown(workerJob: Job) {
    runBlocking { workerJob.join() }
}

/**
 * The runnable entry point for the whisper-server.
 *
 * Reads configuration from the environment, opens the database, starts the Ktor engine
 * with the transcription routes, starts the worker loop, and adds a shutdown hook that
 * stops the server, joins the worker, recovers interrupted jobs, and closes the database.
 *
 * Env vars (see WhisperConfig):
 * - BREAKER_WHISPER_HOST: optional, default "127.0.0.1"
 * - BREAKER_WHISPER_PORT: required
 * - BREAKER_WHISPER_DB: required
 * - BREAKER_WHISPER_FORWARDER_URL: required
 */
fun main() {
    val config = WhisperConfig.fromEnv()
    val db = SqliteDatabase.open(config.databasePath)
    val store = buildJobStore(db, config)
    val forwarder = HttpTranscriptionForwarder(
        DownstreamConfig(
            baseUrl = config.forwarderBaseUrl,
            wordTimestamps = config.wordTimestamps,
        )
    )
    val worker = QueueWorker(store, forwarder)
    val api = buildTranscriptionApi(store, worker::wake, config, System::nanoTime)

    val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val workerJob = workerScope.launch {
        worker.run()
    }

    var stopServer: () -> Unit = {}
    val shutdown = WhisperShutdown({ stopServer() }, workerJob, store, db)
    val server = WhisperEngine.start(api, config.bindAddress, config.port) { shutdown.run() }
    stopServer = { server.stop(1000, 5000) }
    blockUntilShutdown(workerJob)
}
