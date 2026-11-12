package dev.breaker.server.whisper

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import dev.breaker.server.whisper.config.WhisperConfig
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.http.multipartBody
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The api built FROM THE CONFIGURATION by buildTranscriptionApi: the token service is a local
 *  fake that answers every token as a live session token, so only the wiring decides the per-account limit. */
internal class UserLimitWiringTest {

    private lateinit var tokenService: HttpServer
    private val opened = ArrayList<AutoCloseable>()

    @Before
    fun startTokenService() {
        tokenService = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val handler = object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                val bytes = "{\"account_id\":1,\"kind\":\"session\",\"role\":\"user\"}".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        tokenService.createContext("/internal/v1/token/use", handler)
        tokenService.createContext("/internal/v1/token/peek", handler)
        tokenService.start()
    }

    @After
    fun stopEverything() {
        tokenService.stop(0)
        opened.forEach { it.close() }
    }

    private fun openStore(): JobStore {
        val temp = TestDatabases.openTemp()
        opened.add(temp)
        return JobFixtures.store(temp, MutableClock(JobFixtures.START))
    }

    private fun config(limitPerMin: Int?): WhisperConfig = WhisperConfig(
        bindAddress = "127.0.0.1",
        port = 8080,
        databasePath = "/tmp/whisper-wiring.db",
        forwarderBaseUrl = "http://whisper-x:9000",
        userRateLimitPerMin = limitPerMin,
        syncApiBaseUrl = "http://127.0.0.1:${tokenService.address.port}",
        syncApiServiceKey = "svc-key",
    )

    @Test
    fun `with the setting absent the built server never answers 429`() {
        val api = buildTranscriptionApi(openStore(), {}, config(null), { 0L })
        testApplication {
            application { api.install(this) }
            repeat(5) {
                val response: HttpResponse = client.post("/v1/audio/transcriptions") {
                    header("Authorization", "Bearer tok-s")
                    setBody(multipartBody(file = JobFixtures.audio(1), model = "m"))
                }
                assertEquals("no limit is set, so every request is accepted", 202, response.status.value)
            }
        }
    }

    @Test
    fun `with the setting at two the built server answers 429 on the third request`() {
        val api = buildTranscriptionApi(openStore(), {}, config(2), { 0L })
        testApplication {
            application { api.install(this) }
            val statuses = (1..3).map {
                client.post("/v1/audio/transcriptions") {
                    header("Authorization", "Bearer tok-s")
                    setBody(multipartBody(file = JobFixtures.audio(1), model = "m"))
                }.status.value
            }
            assertEquals("two requests pass and the third is over the cap", listOf(202, 202, 429), statuses)
        }
    }
}
