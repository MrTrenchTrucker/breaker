package dev.breaker.server.syncapi.status

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Whether whisper-server is up, as the status route reports it. The answer is cached for
 * [CACHE_WINDOW_MS] (failures too) and a window has one probe at a time: concurrent callers wait
 * on the lock and then read the answer the first one stored, so a signed-in user cannot make
 * sync-api hammer whisper-server. [probe] and [clock] are injected so a test needs no socket and
 * no sleep. Any exception from the probe means down; a cancellation is rethrown and nothing is cached.
 */
internal class StatusProbe(
    private val url: String,
    private val probe: suspend (String) -> Boolean,
    private val clock: Clock,
) {
    companion object {
        const val CACHE_WINDOW_MS: Long = 10_000L
    }

    private val lock = Mutex()
    private var cachedUp: Boolean? = null
    private var checkedAtMs: Long = 0L

    suspend fun isUp(): Boolean = lock.withLock {
        val known = cachedUp
        if (known != null && clock.millis() - checkedAtMs < CACHE_WINDOW_MS) {
            return@withLock known
        }
        val answer = try {
            probe(url)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        cachedUp = answer
        checkedAtMs = clock.millis()
        answer
    }
}

/**
 * The real probe: one GET, 1 second for the connection and 1 second for the answer, no redirect
 * followed (a 3xx is not a 200), the body discarded and never read. Only HTTP 200 is up.
 */
internal suspend fun probeWhisperHealth(url: String): Boolean = withContext(Dispatchers.IO) {
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1)).GET().build()
    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
}
