package dev.breaker.server.whisper

import dev.breaker.server.whisper.config.WhisperConfig
import dev.breaker.server.whisper.db.SqliteDatabase
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

private const val UNPARSEABLE_FORWARDING_TO = "unparseable"

/** A port substring is usable only when it is 1-5 ASCII digits and its value is 1..65535; an empty
 *  or out-of-range port makes the whole URL unparseable. */
private fun isValidPort(port: String): Boolean {
    if (port.isEmpty() || port.length > 5) return false
    for (c in port) if (c !in '0'..'9') return false
    return port.toInt() in 1..65535
}

/**
 * Reduces a configured forwarding URL to scheme + host + port, so the /health route can name the
 * upstream without ever echoing a userinfo, path, query or fragment - any of which can carry a
 * token. The port is included only when the URL carried one. The last '@' in the authority splits
 * userinfo from host:port, so a password that itself contains '@' still resolves. An unparseable
 * URL, or one with no scheme or no host, or a port that is not 1-5 digits in 1..65535, reports the
 * fixed marker "unparseable", never the raw text.
 */
internal fun sanitiseForwardingTo(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return UNPARSEABLE_FORWARDING_TO
    val scheme = url.substring(0, schemeEnd)
    if (scheme != "http" && scheme != "https") return UNPARSEABLE_FORWARDING_TO
    val afterScheme = schemeEnd + 3
    var authorityEnd = afterScheme
    while (authorityEnd < url.length && url[authorityEnd] != '/' && url[authorityEnd] != '?' && url[authorityEnd] != '#') {
        authorityEnd++
    }
    val authority = url.substring(afterScheme, authorityEnd)
    if (authority.isEmpty()) return UNPARSEABLE_FORWARDING_TO
    val hostPort = authority.substringAfterLast('@')
    if (hostPort.isEmpty()) return UNPARSEABLE_FORWARDING_TO
    val host: String
    val port: String?
    if (hostPort.startsWith("[")) { // IPv6 literal keeps its brackets, e.g. http://[::1]:9000
        val closeBracket = hostPort.indexOf(']')
        if (closeBracket < 0) return UNPARSEABLE_FORWARDING_TO
        host = hostPort.substring(0, closeBracket + 1)
        val afterBracket = hostPort.substring(closeBracket + 1)
        port = if (afterBracket.startsWith(":")) afterBracket.substring(1) else null
    } else {
        val colon = hostPort.indexOf(':')
        if (colon < 0) {
            host = hostPort
            port = null
        } else {
            host = hostPort.substring(0, colon)
            port = hostPort.substring(colon + 1)
        }
    }
    if (host.isEmpty() || host == "[]") return UNPARSEABLE_FORWARDING_TO
    if (port != null && !isValidPort(port)) return UNPARSEABLE_FORWARDING_TO
    return if (port != null) "$scheme://$host:$port" else "$scheme://$host"
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
 * The two values the /health route echoes, computed once at startup: the sanitised forwarding
 * target and a provider that renders the uptime on demand from a monotonic source. The provider -
 * not a captured string - is what lets uptime advance as the source moves between requests.
 */
internal fun startupHealth(config: WhisperConfig, monotonicNanos: () -> Long): Pair<String, () -> String> =
    sanitiseForwardingTo(config.forwarderBaseUrl) to uptimeProvider(monotonicNanos)

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
    val (forwardingTo, uptime) = startupHealth(config, monotonicNanos)
    return TranscriptionApi(store, wakeWorker, forwardingTo = forwardingTo, uptime = uptime)
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
        DownstreamConfig(baseUrl = config.forwarderBaseUrl)
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
}
