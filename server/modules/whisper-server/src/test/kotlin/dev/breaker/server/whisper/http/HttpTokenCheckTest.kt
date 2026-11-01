package dev.breaker.server.whisper.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Before
import org.junit.Test

internal class HttpTokenCheckTest {

    private val key = "test-key"
    private val token = "tok-abc"
    private val liveBody = """{"account_id":7,"kind":"session","role":"user"}"""
    private lateinit var server: HttpServer

    private val recordedPath = AtomicReference<String?>(null)
    private val recordedMethod = AtomicReference<String?>(null)
    private val recordedServiceKey = AtomicReference<String?>(null)
    private val recordedPresentedToken = AtomicReference<String?>(null)
    private var delayMillis: Long = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val handler = object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                recordedPath.set(exchange.requestURI.path)
                recordedMethod.set(exchange.requestMethod)
                recordedServiceKey.set(exchange.requestHeaders.getFirst("X-Service-Key"))
                recordedPresentedToken.set(exchange.requestHeaders.getFirst("X-Presented-Token"))
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis)
                }
                val code = when (exchange.requestURI.path) {
                    "/internal/v1/token/use", "/internal/v1/token/peek" -> 200
                    else -> 404
                }
                val body = if (code == 200) liveBody else "{}"
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.createContext("/internal/v1/token/use", handler)
        server.createContext("/internal/v1/token/peek", handler)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun port(): Int = server.address.port

    // A port that was free a moment ago and has no listener: a connect to it is refused at once.
    // (A server that is created and stopped without being started still accepts the connect and never answers.)
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun client(baseUrl: String = "http://127.0.0.1:${port()}",
                       serviceKey: String = key,
                       connectTimeout: Duration = Duration.ofSeconds(3),
                       totalTimeout: Duration = Duration.ofSeconds(5)): HttpTokenCheck =
        HttpTokenCheck(baseUrl, serviceKey, connectTimeout, totalTimeout)

    private fun use(token: String, client: HttpTokenCheck = client()): TokenAnswer =
        runBlocking { client.use(token) }

    private fun peek(token: String, client: HttpTokenCheck = client()): TokenAnswer =
        runBlocking { client.peek(token) }

    @Test
    fun `a 200 answer with an account number is live for use`() {
        val answer = use(token)
        assertEquals(TokenAnswer.Live(7), answer)
        assertEquals("/internal/v1/token/use", recordedPath.get())
        assertEquals("POST", recordedMethod.get())
        assertEquals(key, recordedServiceKey.get())
        assertEquals(token, recordedPresentedToken.get())
    }

    @Test
    fun `the same for peek`() {
        val answer = peek(token)
        assertEquals(TokenAnswer.Live(7), answer)
        assertEquals("/internal/v1/token/peek", recordedPath.get())
    }

    @Test
    fun `use posts to the use path and peek to the peek path with both headers`() {
        use(token)
        assertEquals("/internal/v1/token/use", recordedPath.get())
        assertEquals(key, recordedServiceKey.get())
        assertEquals(token, recordedPresentedToken.get())

        peek(token)
        assertEquals("/internal/v1/token/peek", recordedPath.get())
        assertEquals(key, recordedServiceKey.get())
        assertEquals(token, recordedPresentedToken.get())
    }

    @Test
    fun `a 401 answer is not live`() {
        val fortyOne = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fortyOne.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(401, 0)
            exchange.responseBody.use { }
        }
        fortyOne.start()
        try {
            val answer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${fortyOne.address.port}", key)
                    .use(token)
            }
            assertEquals(TokenAnswer.NotLive, answer)
        } finally {
            fortyOne.stop(0)
        }
    }

    @Test
    fun `a 403 answer is unavailable`() {
        val fourOhThree = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fourOhThree.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(403, 0)
            exchange.responseBody.use { }
        }
        fourOhThree.start()
        try {
            val answer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${fourOhThree.address.port}", key)
                    .use(token)
            }
            assertEquals(TokenAnswer.Unavailable, answer)
        } finally {
            fourOhThree.stop(0)
        }
    }

    @Test
    fun `a 500 answer is unavailable`() {
        val fiveHundred = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fiveHundred.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.use { }
        }
        fiveHundred.start()
        try {
            val answer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${fiveHundred.address.port}", key)
                    .use(token)
            }
            assertEquals(TokenAnswer.Unavailable, answer)
        } finally {
            fiveHundred.stop(0)
        }
    }

    @Test
    fun `a 200 answer without an account number is unavailable`() {
        val noAccount = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        noAccount.createContext("/internal/v1/token/use") { exchange ->
            val body = """{"kind":"session","role":"user"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        noAccount.start()
        try {
            val answer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${noAccount.address.port}", key)
                    .use(token)
            }
            assertEquals(TokenAnswer.Unavailable, answer)
        } finally {
            noAccount.stop(0)
        }
    }

    @Test
    fun `a refused connection is unavailable`() {
        val refusedPort = closedPort()
        val answer = runBlocking {
            HttpTokenCheck("http://127.0.0.1:$refusedPort", key)
                .use(token)
        }
        assertEquals(TokenAnswer.Unavailable, answer)
    }

    @Test(timeout = 20_000)
    fun `an answer slower than the total timeout is unavailable`() {
        // The fake answers 200 with an account number after 7 s: with the 300 ms total timeout the client gives up first.
        delayMillis = 7_000
        val answer = runBlocking {
            HttpTokenCheck("http://127.0.0.1:${port()}", key,
                connectTimeout = Duration.ofSeconds(3),
                totalTimeout = Duration.ofMillis(300))
                .use(token)
        }
        assertEquals(TokenAnswer.Unavailable, answer)
    }

    @Test
    fun `a token with a control character or a space or no characters is not live and is never sent`() {
        for (bad in listOf("", "tok\u0001abc", "tok abc", "tok\tabc", "t\u00F6k")) {
            assertEquals("answer for the token: $bad", TokenAnswer.NotLive, use(bad))
        }
        assertNull("no request reached the fake", recordedPath.get())
    }

    @Test
    fun `a blank address or a blank key is refused when the client is built`() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpTokenCheck("", key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpTokenCheck("http://127.0.0.1:${port()}", "")
        }
    }

    @Test
    fun `the presented token and the service key never leak into stdio`() {
        val outCapture = ByteArrayOutputStream()
        val errCapture = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outCapture, true))
        System.setErr(PrintStream(errCapture, true))
        try {
            val refusedPort = closedPort()
            runBlocking {
                HttpTokenCheck("http://127.0.0.1:$refusedPort", key).use(token)
            }
            runBlocking {
                HttpTokenCheck("http://127.0.0.1:${port()}", key).use(token)
            }
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        val out = outCapture.toString()
        val err = errCapture.toString()
        assertFalse("test-key must not appear in stdio", out.contains("test-key") || err.contains("test-key"))
        assertFalse("tok-abc must not appear in stdio", out.contains("tok-abc") || err.contains("tok-abc"))
    }
}
