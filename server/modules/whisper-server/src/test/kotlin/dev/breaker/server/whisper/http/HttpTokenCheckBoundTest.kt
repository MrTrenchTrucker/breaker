package dev.breaker.server.whisper.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

internal class HttpTokenCheckBoundTest {

    private val key = "test-key"
    private val token = "tok-abc"
    private val liveBody = "{\"account_id\":7,\"kind\":\"session\",\"role\":\"user\"}"

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    // body of 4097 bytes with a good account number at the start: Unavailable
    @Test
    fun `a body over the cap with a good account number at the start is unavailable`() {
        val oversized = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        oversized.createContext("/internal/v1/token/use") { exchange ->
            // Two writes with a pause between them: the good account number arrives alone first,
            // then the rest, which takes the body past the cap (4097 bytes in all).
            val first = liveBody.toByteArray(Charsets.UTF_8)
            val rest = " ".repeat(4097 - liveBody.length).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, (first.size + rest.size).toLong())
            exchange.responseBody.use { out ->
                out.write(first)
                out.flush()
                Thread.sleep(100)
                out.write(rest)
            }
        }
        oversized.start()
        try {
            val answer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${oversized.address.port}", key)
                    .use(token)
            }
            assertEquals("oversized body with good account_id", TokenAnswer.Unavailable, answer)
        } finally {
            oversized.stop(0)
        }
    }

    // exactly 4096 bytes whose start is {"account_id":7, padded to 4096 with spaces before the closing brace: Live(7)
    @Test
    fun `exactly 4096 bytes with account_id at the start is live`() {
        val exactly4096 = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        exactly4096.createContext("/internal/v1/token/use") { exchange ->
            val prefix = "{\"account_id\":7,"
            val padding = " ".repeat(4096 - prefix.length - 1)
            val body = prefix + padding + "}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        exactly4096.start()
        try {
            val answer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${exactly4096.address.port}", key)
                    .use(token)
            }
            assertEquals("exactly 4096 bytes with account_id", TokenAnswer.Live(7), answer)
        } finally {
            exactly4096.stop(0)
        }
    }

    // headers sent at once, then the body held back 7 s, with a 300 ms total timeout: Unavailable
    @Test(timeout = 20_000)
    fun `headers sent then body held back beyond total timeout is unavailable`() {
        val heldBack = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        heldBack.createContext("/internal/v1/token/use") { exchange ->
            val body = liveBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { out ->
                out.flush()
                Thread.sleep(7_000)
                out.write(body)
            }
        }
        heldBack.start()
        try {
            val answer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${heldBack.address.port}", key,
                    connectTimeout = Duration.ofSeconds(3),
                    totalTimeout = Duration.ofMillis(300))
                    .use(token)
            }
            assertEquals("body held back past total timeout", TokenAnswer.Unavailable, answer)
        } finally {
            heldBack.stop(0)
        }
    }

    // a body whose first chunk holds {"account_id":7 and whose rest is held back 7 s, 300 ms total: Unavailable (not Live)
    @Test(timeout = 20_000)
    fun `partial account_id then body held back is unavailable not live`() {
        val partialHeld = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        partialHeld.createContext("/internal/v1/token/use") { exchange ->
            val firstChunk = "{\"account_id\":7".toByteArray(Charsets.UTF_8)
            val restChunk = ",\"kind\":\"session\",\"role\":\"user\"}".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, (firstChunk.size + restChunk.size).toLong())
            exchange.responseBody.use { out ->
                out.write(firstChunk)
                out.flush()
                Thread.sleep(7_000)
                out.write(restChunk)
            }
        }
        partialHeld.start()
        try {
            val answer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${partialHeld.address.port}", key,
                    connectTimeout = Duration.ofSeconds(3),
                    totalTimeout = Duration.ofMillis(300))
                    .use(token)
            }
            assertEquals("partial account_id then held body", TokenAnswer.Unavailable, answer)
        } finally {
            partialHeld.stop(0)
        }
    }

    // control: a normal small 200 gives Live(7) and a 401 gives NotLive (one test for both)
    @Test
    fun `control small 200 is live and 401 is not live`() {
        val control = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        control.createContext("/internal/v1/token/live") { exchange ->
            val bytes = liveBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        control.createContext("/internal/v1/token/unauthorized") { exchange ->
            exchange.sendResponseHeaders(401, 0)
            exchange.responseBody.use { }
        }
        control.start()
        try {
            val liveAnswer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${control.address.port}/internal/v1/token/live", key)
                    .use(token)
            }
            assertEquals("small 200 is live", TokenAnswer.Live(7), liveAnswer)
            val notLiveAnswer = runBlocking {
                HttpTokenCheck("http://127.0.0.1:${control.address.port}/internal/v1/token/unauthorized", key)
                    .use(token)
            }
            assertEquals("401 is not live", TokenAnswer.NotLive, notLiveAnswer)
        } finally {
            control.stop(0)
        }
    }

    // refused-connection control: a closed port is refused at once
    @Test
    fun `a refused connection is unavailable`() {
        val refusedPort = closedPort()
        val answer = runBlocking {
            HttpTokenCheck("http://127.0.0.1:$refusedPort", key)
                .use(token)
        }
        assertEquals("refused connection", TokenAnswer.Unavailable, answer)
    }
}
