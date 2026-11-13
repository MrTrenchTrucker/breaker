package dev.breaker.server.whisper.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// The box_pubkey member of a live token-check answer. It is kept only when it is a JSON string of standard
// base64 that decodes to exactly 32 bytes; absent, null, a number, an empty string, text that is not base64 and a
// key of the wrong length all give no key (null), and the answer is still Live: a bad key never fails the check.
// Built like HttpTokenCheckAccountIdTest: a com.sun.net.httpserver.HttpServer on 127.0.0.1:0.
internal class HttpTokenCheckBoxPubkeyTest {

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

    private fun answer(): TokenAnswer {
        val client = HttpTokenCheck("http://127.0.0.1:${server.address.port}", key, Duration.ofSeconds(3), Duration.ofSeconds(5))
        return runBlocking { client.use(liveToken) }
    }

    private fun textOf(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size) { index -> (index + 1).toByte() })

    private fun reply(member: String) {
        replyBody = "{\"account_id\":7,\"kind\":\"session\",\"role\":\"user\"$member}"
    }

    @Test
    fun `a key of thirty two bytes is carried in the live answer`() {
        val text = textOf(32)
        reply(",\"box_pubkey\":\"$text\"")
        assertEquals("whisper: a 32-byte key", TokenAnswer.Live(7L, "session", text), answer())
    }

    @Test
    fun `a null box_pubkey is a live answer with no key`() {
        reply(",\"box_pubkey\":null")
        assertEquals("whisper: null key", TokenAnswer.Live(7L, "session", null), answer())
    }

    @Test
    fun `an answer with no box_pubkey member is a live answer with no key`() {
        reply("")
        assertEquals("whisper: absent key", TokenAnswer.Live(7L, "session", null), answer())
    }

    @Test
    fun `a box_pubkey that is a number or an empty string is no key`() {
        reply(",\"box_pubkey\":12")
        assertEquals("whisper: numeric key", TokenAnswer.Live(7L, "session", null), answer())
        reply(",\"box_pubkey\":\"\"")
        assertEquals("whisper: empty key", TokenAnswer.Live(7L, "session", null), answer())
    }

    @Test
    fun `a box_pubkey that is not base64 is no key`() {
        reply(",\"box_pubkey\":\"not base64 !!\"")
        assertEquals("whisper: text that is not base64", TokenAnswer.Live(7L, "session", null), answer())
    }

    @Test
    fun `a box_pubkey of the wrong length is no key`() {
        for (size in listOf(16, 31, 33, 64)) {
            reply(",\"box_pubkey\":\"${textOf(size)}\"")
            assertEquals("whisper: a $size-byte key", TokenAnswer.Live(7L, "session", null), answer())
        }
    }
}
