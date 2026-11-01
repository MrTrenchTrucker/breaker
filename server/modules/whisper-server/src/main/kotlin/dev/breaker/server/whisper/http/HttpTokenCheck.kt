package dev.breaker.server.whisper.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private suspend fun check(path: String, token: String): TokenAnswer {
        // A real token is visible ASCII. The JDK refuses any other header value, so such a token never leaves this process.
        if (token.isEmpty() || token.any { it.code !in 0x21..0x7E }) return TokenAnswer.NotLive
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${baseUrl.trimEnd('/')}$path"))
            .timeout(totalTimeout)
            .header("X-Service-Key", serviceKey)
            .header("X-Presented-Token", token)
            .POST(BodyPublishers.noBody())
            .build()
        return withContext(Dispatchers.IO) {
            try {
                val response = client.send(request, BodyHandlers.ofString())
                when (response.statusCode()) {
                    200 -> {
                        val body = response.body() ?: return@withContext TokenAnswer.Unavailable
                        val match = accountIdRegex.find(body) ?: return@withContext TokenAnswer.Unavailable
                        TokenAnswer.Live(match.groupValues[1].toLong())
                    }
                    401 -> TokenAnswer.NotLive
                    else -> TokenAnswer.Unavailable
                }
            } catch (e: IOException) {
                TokenAnswer.Unavailable
            }
        }
    }
}
