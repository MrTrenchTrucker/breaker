package dev.breaker.server.whisper.http

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class HttpTokenCheckEarlyStopTest {

    private val key = "test-key"
    private val token = "tok-abc"
    private val chunk = ByteArray(1024) { 65 }

    // The server answers 200 with Content-Length 100 MB and writes 1024-byte
    // chunks one at a time with a 1 ms pause. The client cancels at the 4096
    // byte cap after about 4-5 chunks; the server's next writes fail with
    // IOException within a few chunks (a loaded machine may need some hundred).
    // A cap raised x1000 would need about 4000 chunks, so the bound of 1500 still
    // tells the two apart. The latch is released by the failed write,
    // tracked by failedWrite, not by the server running to 20000 chunks.
    @Test(timeout = 20_000)
    fun earlyStopReadsFewerThan1500Chunks() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val chunksWritten = AtomicInteger(0)
        val failedWrite = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        server.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(200, 100L * 1024L * 1024L)
            exchange.responseBody.flush()
            val out = exchange.responseBody
            try {
                for (i in 0 until 20000) {
                    Thread.sleep(1)
                    try {
                        out.write(chunk)
                        out.flush()
                        chunksWritten.incrementAndGet()
                    } catch (e: IOException) {
                        failedWrite.set(true)
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
            val client = HttpTokenCheck("http://127.0.0.1:${server.address.port}", key)
            val answer = runBlocking { client.use(token) }
            assertEquals("early stop answer is Unavailable",
                TokenAnswer.Unavailable, answer)
            assertTrue("latch released by failed write within 8 s",
                latch.await(8, TimeUnit.SECONDS))
            assertTrue("server saw a failed write", failedWrite.get())
            assertTrue("chunk count at failed write below 1500",
                chunksWritten.get() < 1500)
        } finally {
            server.stop(0)
        }
    }

    // Control: a normal short answer still gives Live(7).
    @Test(timeout = 20_000)
    fun shortAnswerIsLive() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = "{\"account_id\":7}"
        val bytes = body.toByteArray()
        server.createContext("/internal/v1/token/use") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = HttpTokenCheck("http://127.0.0.1:${server.address.port}", key)
            val answer = runBlocking { client.use(token) }
            assertEquals("short answer is Live(7)", TokenAnswer.Live(7), answer)
        } finally {
            server.stop(0)
        }
    }
}
