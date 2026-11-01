package dev.breaker.server.whisper.http

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

// A strict account_id parse: "account_id", colon, 1-18 digits only, then optional
// white space and a comma or closing brace. 19+ digits, "12abc", "7.5", "-3",
// no number, or a non-object body -> Unavailable, never Live. Built like
// HttpTokenCheckTest's fakes: a com.sun.net.httpserver.HttpServer on 127.0.0.1:0.
internal class HttpTokenCheckAccountIdTest {

    private val key = "test-key"
    private val liveToken = "tok-abc"

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
                       serviceKey: String = key,
                       connectTimeout: Duration = Duration.ofSeconds(3),
                       totalTimeout: Duration = Duration.ofSeconds(5)): HttpTokenCheck =
        HttpTokenCheck(baseUrl, serviceKey, connectTimeout, totalTimeout)

    private fun answer(token: String = liveToken, client: HttpTokenCheck = client()): TokenAnswer =
        runBlocking { client.use(token) }

    @Test
    fun `a nineteen digit account id is unavailable`() {
        replyBody = "{\"account_id\":${"1".repeat(19)},\"kind\":\"session\",\"role\":\"user\"}"
        assertEquals("19-digit account id", TokenAnswer.Unavailable, answer())
    }

    @Test
    fun `a twenty digit account id is unavailable`() {
        replyBody = "{\"account_id\":${"1".repeat(20)},\"kind\":\"session\",\"role\":\"user\"}"
        assertEquals("20-digit account id", TokenAnswer.Unavailable, answer())
    }

    @Test
    fun `an account id followed by letters is unavailable`() {
        replyBody = "{\"account_id\":12abc,\"kind\":\"session\",\"role\":\"user\"}"
        assertEquals("account id with letters", TokenAnswer.Unavailable, answer())
    }

    @Test
    fun `a normal id stays live`() {
        replyBody = "{\"account_id\":7,\"kind\":\"session\",\"role\":\"user\"}"
        assertEquals("normal id is live", TokenAnswer.Live(7), answer())
    }

    @Test
    fun `an eighteen digit account id stays live`() {
        replyBody = "{\"account_id\":${"9".repeat(18)},\"kind\":\"session\",\"role\":\"user\"}"
        assertEquals("18-digit boundary is live", TokenAnswer.Live("9".repeat(18).toLong()), answer())
    }

    @Test
    fun `a decimal and a negative account id are unavailable`() {
        for (body in listOf("{\"account_id\":7.5,\"kind\":\"session\",\"role\":\"user\"}",
                            "{\"account_id\":-3,\"kind\":\"session\",\"role\":\"user\"}")) {
            replyBody = body
            assertEquals("decimal/negative account id", TokenAnswer.Unavailable, answer())
        }
    }

    @Test
    fun `no account id key is unavailable`() {
        replyBody = "{\"kind\":\"session\",\"role\":\"user\"}"
        assertEquals("no account id key", TokenAnswer.Unavailable, answer())
    }
}
