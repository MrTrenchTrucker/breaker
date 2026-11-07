package dev.breaker.server.whisper

import dev.breaker.server.whisper.http.HttpTokenCheck
import dev.breaker.server.whisper.http.TokenAnswer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Runs the real [HttpTokenCheck] against a local fake server so the kind-parse path is
 *  exercised end to end (addition A: a wrong parser would pass all route tests
 *  because they use a fake TokenCheck). */
internal class AgentLimitKindParseTest {

    private lateinit var server: HttpServer
    private var replyBody: String = ""

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val handler = object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                val bytes = replyBody.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.createContext("/internal/v1/token/use", handler)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun port(): Int = server.address.port

    private fun client(baseUrl: String = "http://127.0.0.1:${port()}",
                       serviceKey: String = "svc-key"): HttpTokenCheck =
        HttpTokenCheck(baseUrl, serviceKey, Duration.ofSeconds(3), Duration.ofSeconds(5))

    private fun answer(token: String = "tok-agent", client: HttpTokenCheck = client()): TokenAnswer =
        runBlocking { client.use(token) }

    @Test
    fun `kind agent is read as agent`() {
        replyBody = "{\"account_id\":7,\"kind\":\"agent\",\"role\":\"user\"}"
        assertEquals("live agent", TokenAnswer.Live(7, "agent"), answer())
    }

    @Test
    fun `kind session is read as session`() {
        replyBody = "{\"account_id\":7,\"kind\":\"session\",\"role\":\"user\"}"
        assertEquals("live session", TokenAnswer.Live(7, "session"), answer())
    }

    @Test
    fun `kind missing gives not limited (defaults to session)`() {
        replyBody = "{\"account_id\":7,\"role\":\"user\"}"
        assertEquals("missing kind defaults to session", TokenAnswer.Live(7, "session"), answer())
    }

    @Test
    fun `an unknown kind gives not limited`() {
        replyBody = "{\"account_id\":7,\"kind\":\"bot\",\"role\":\"user\"}"
        assertEquals("unknown kind is still live but not agent", TokenAnswer.Live(7, "bot"), answer())
    }

    @Test
    fun `account id first then kind parses`() {
        replyBody = "{\"account_id\":42,\"kind\":\"agent\"}"
        assertEquals("account first order", TokenAnswer.Live(42, "agent"), answer())
    }

    @Test
    fun `kind first then account id parses`() {
        replyBody = "{\"kind\":\"agent\",\"account_id\":42}"
        assertEquals("kind first order", TokenAnswer.Live(42, "agent"), answer())
    }
}
