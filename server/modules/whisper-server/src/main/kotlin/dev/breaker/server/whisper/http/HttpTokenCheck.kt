package dev.breaker.server.whisper.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.text.Regex

internal class HttpTokenCheck(
    private val baseUrl: String,
    private val serviceKey: String,
    private val connectTimeout: Duration = Duration.ofSeconds(3),
    private val totalTimeout: Duration = Duration.ofSeconds(5),
) : TokenCheck {

    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(serviceKey.isNotBlank()) { "serviceKey must not be blank" }
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build()

    private val accountIdRegex = Regex("\"account_id\"\\s*:\\s*(\\d{1,18})")

    override suspend fun use(token: String): TokenAnswer =
        check("/internal/v1/token/use", token)

    override suspend fun peek(token: String): TokenAnswer =
        check("/internal/v1/token/peek", token)

    internal companion object {
        internal const val MAX_TOKEN_ANSWER_BYTES: Int = 4096
    }

    private suspend fun check(path: String, token: String): TokenAnswer {
        // A real token is visible ASCII. The JDK refuses any other header value, so such a token never leaves this process.
        if (token.isEmpty() || token.any { it.code !in 0x21..0x7E }) return TokenAnswer.NotLive
        return withContext(Dispatchers.IO) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("${baseUrl.trimEnd('/')}$path"))
                    .timeout(totalTimeout)
                    .header("X-Service-Key", serviceKey)
                    .header("X-Presented-Token", token)
                    .POST(BodyPublishers.noBody())
                    .build()
                val response = sendAsync(request)
                when (response.statusCode()) {
                    200 -> {
                        val bodyBytes = response.body() ?: return@withContext TokenAnswer.Unavailable
                        val body = if (bodyBytes.size > MAX_TOKEN_ANSWER_BYTES) null else String(bodyBytes, Charsets.UTF_8)
                        val match = body?.let { accountIdRegex.find(it) } ?: return@withContext TokenAnswer.Unavailable
                        TokenAnswer.Live(match.groupValues[1].toLong())
                    }
                    401 -> TokenAnswer.NotLive
                    else -> TokenAnswer.Unavailable
                }
            } catch (e: IOException) {
                TokenAnswer.Unavailable
            } catch (e: Exception) {
                val root = generateSequence<Throwable>(e) { it.cause }.last()
                if (root is kotlinx.coroutines.CancellationException) throw root
                TokenAnswer.Unavailable
            }
        }
    }

    private suspend fun sendAsync(request: HttpRequest): HttpResponse<ByteArray?> =
        suspendCancellableCoroutine { continuation ->
            val future = client.sendAsync(request, boundedBody(MAX_TOKEN_ANSWER_BYTES))
                .orTimeout(totalTimeout.toMillis(), TimeUnit.MILLISECONDS)
            future.whenComplete { response, error ->
                if (error != null) continuation.resumeWithException(error) else continuation.resume(response)
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
}
