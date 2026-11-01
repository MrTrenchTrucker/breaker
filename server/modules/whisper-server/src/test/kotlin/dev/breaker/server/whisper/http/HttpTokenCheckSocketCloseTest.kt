package dev.breaker.server.whisper.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class HttpTokenCheckSocketCloseTest {

    private val key = "test-key"
    private val token = "tok-abc"

    // THE MEASURED CASE: dribbling server, one byte every 100 ms for 8 s, totalTimeout 400 ms.
    // With the fix the subscriber cancels at the deadline so the socket closes and the server
    // sees its write fail within 2500 ms of server start.
    @Test(timeout = 20_000)
    fun dribblingServerClosesSocketAtDeadline() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val writeFailedAt = AtomicLong(-1)
        val latch = CountDownLatch(1)
        val start = System.nanoTime()
        server.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(200, 100000L)
            exchange.responseBody.flush()
            val out = exchange.responseBody
            try {
                for (i in 0 until 80) {
                    Thread.sleep(100)
                    try {
                        out.write(byteArrayOf(65))
                        out.flush()
                    } catch (e: java.io.IOException) {
                        writeFailedAt.set((System.nanoTime() - start) / 1_000_000L)
                        return@createContext
                    }
                }
            } finally {
                try { out.close() } catch (e: Exception) { }
                latch.countDown()
            }
        }
        server.start()
        try {
            val client = HttpTokenCheck("http://127.0.0.1:${server.address.port}", key,
                connectTimeout = Duration.ofSeconds(3), totalTimeout = Duration.ofMillis(400))
            val answer = runBlocking { client.use(token) }
            assertEquals("dribbling server returns Unavailable at the deadline",
                TokenAnswer.Unavailable, answer)
            assertTrue("server write failed within 2500 ms of start (was ${writeFailedAt.get()} ms)",
                latch.await(3, TimeUnit.SECONDS) && writeFailedAt.get() >= 0 && writeFailedAt.get() < 2500)
        } finally {
            server.stop(0)
        }
    }

    // Control: a fast normal answer still gives Live(7).
    @Test(timeout = 20_000)
    fun fastNormalAnswerIsLive() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = "{\"account_id\":7,\"kind\":\"session\",\"role\":\"user\"}"
        val bytes = body.toByteArray()
        server.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = HttpTokenCheck("http://127.0.0.1:${server.address.port}", key)
            val answer = runBlocking { client.use(token) }
            assertEquals("fast normal answer is Live(7)",
                TokenAnswer.Live(7), answer)
        } finally {
            server.stop(0)
        }
    }

    // Control: a 401 still gives NotLive.
    @Test(timeout = 20_000)
    fun fourOhOneIsNotLive() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(401, 0)
            exchange.responseBody.use { }
        }
        server.start()
        try {
            val client = HttpTokenCheck("http://127.0.0.1:${server.address.port}", key)
            val answer = runBlocking { client.use(token) }
            assertEquals("401 is NotLive", TokenAnswer.NotLive, answer)
        } finally {
            server.stop(0)
        }
    }

    // Control: a body complete before the deadline is not cancelled - Live(7) with no timeout.
    // A timer left running would fire later as a no-op on a completed future, so no test can
    // observe that; this test only proves a complete body is kept.
    @Test(timeout = 20_000)
    fun completeBeforeDeadlineIsNotCancelled() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = "{\"account_id\":7,\"kind\":\"session\",\"role\":\"user\"}"
        val bytes = body.toByteArray()
        server.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = HttpTokenCheck("http://127.0.0.1:${server.address.port}", key,
                connectTimeout = Duration.ofSeconds(3), totalTimeout = Duration.ofMillis(400))
            val answer = runBlocking { client.use(token) }
            assertEquals("complete body before deadline is Live(7)",
                TokenAnswer.Live(7), answer)
        } finally {
            server.stop(0)
        }
    }
}
