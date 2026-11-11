package dev.breaker.server.syncapi.status

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cache window and the single flight of [StatusProbe], played through injected ports: no socket, no sleep. */
internal class StatusProbeTest {

    private class StepClock(var nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }

    private val clock = StepClock(1_000_000)
    private val calls = AtomicInteger(0)

    private fun probeOf(answer: Boolean) = StatusProbe("http://whisper.test/health", { calls.incrementAndGet(); answer }, clock)

    @Test
    fun `an answer of up is reported and probed once`() {
        val status = probeOf(true)
        assertEquals("sync-api: up", true, runBlocking { status.isUp() })
        assertEquals("sync-api: one probe", 1, calls.get())
    }

    @Test
    fun `a second ask inside the window is answered from the cache`() {
        val status = probeOf(true)
        runBlocking {
            status.isUp()
            clock.nowMs += 9_999
            status.isUp()
        }
        assertEquals("sync-api: still one probe 1 ms before the window ends", 1, calls.get())
    }

    @Test
    fun `an ask when the window has ended probes again`() {
        val status = probeOf(true)
        runBlocking {
            status.isUp()
            clock.nowMs += 10_000
            status.isUp()
        }
        assertEquals("sync-api: a second probe at the window end", 2, calls.get())
    }

    @Test
    fun `a down answer is cached too`() {
        val status = probeOf(false)
        runBlocking {
            assertEquals("sync-api: down", false, status.isUp())
            clock.nowMs += 5_000
            assertEquals("sync-api: still down from the cache", false, status.isUp())
        }
        assertEquals("sync-api: one probe", 1, calls.get())
    }

    @Test
    fun `the probe is asked with the configured url`() {
        val asked = ArrayList<String>()
        val status = StatusProbe("http://whisper.test/health", { url -> asked.add(url); true }, clock)
        runBlocking { status.isUp() }
        assertEquals("sync-api: the url", listOf("http://whisper.test/health"), asked)
    }

    @Test
    fun `an exception from the probe means down and is cached`() {
        val status = StatusProbe("http://whisper.test/health", { calls.incrementAndGet(); throw IllegalStateException("refused") }, clock)
        runBlocking {
            assertEquals("sync-api: down", false, status.isUp())
            assertEquals("sync-api: down again from the cache", false, status.isUp())
        }
        assertEquals("sync-api: one probe", 1, calls.get())
    }

    @Test
    fun `a cancellation from the probe propagates and caches nothing`() {
        var cancelled = true
        val status = StatusProbe(
            "http://whisper.test/health",
            { calls.incrementAndGet(); if (cancelled) throw CancellationException("stop") else true },
            clock,
        )
        val outcome = runBlocking {
            try {
                status.isUp()
                "returned"
            } catch (stopped: CancellationException) {
                "cancelled"
            }
        }
        assertEquals("sync-api: not swallowed into down", "cancelled", outcome)
        cancelled = false
        assertEquals("sync-api: the next ask probes again and answers", true, runBlocking { status.isUp() })
        assertEquals("sync-api: two probes, nothing was cached", 2, calls.get())
    }

    @Test
    fun `callers that arrive together share one probe`() {
        val gate = CompletableDeferred<Unit>()
        val status = StatusProbe("http://whisper.test/health", { calls.incrementAndGet(); gate.await(); true }, clock)
        val answers = runBlocking {
            val asked = (1..5).map { async { status.isUp() } }
            yield()
            gate.complete(Unit)
            asked.awaitAll()
        }
        assertEquals("sync-api: every caller gets the answer", listOf(true, true, true, true, true), answers)
        assertEquals("sync-api: one probe for all of them", 1, calls.get())
    }

    // ---- The REAL probe against a local server on 127.0.0.1 (port 0): no injected answer.

    private fun realProbe(port: Int): StatusProbe =
        StatusProbe("http://127.0.0.1:$port/health", { url -> probeWhisperHealth(url) }, clock)

    // A server on /health that answers [code] with no body, after [sleepMs], with a Location header when [location] is set.
    private fun serverAnswering(code: Int, location: String? = null, sleepMs: Long = 0L): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            if (sleepMs > 0L) Thread.sleep(sleepMs)
            if (location != null) exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        }
        server.start()
        return server
    }

    @Test(timeout = 20_000)
    fun `a real server answering 200 is up and the same server stopped is down`() {
        val server = serverAnswering(200)
        val port = server.address.port
        try {
            assertEquals("sync-api: a real 200 is up", true, runBlocking { realProbe(port).isUp() })
        } finally {
            server.stop(0)
        }
        assertEquals("sync-api: the stopped server is down", false, runBlocking { realProbe(port).isUp() })
    }

    @Test(timeout = 20_000)
    fun `a redirect to a server that answers 200 is down`() {
        val target = serverAnswering(200)
        val redirecting = serverAnswering(302, location = "http://127.0.0.1:${target.address.port}/health")
        try {
            assertEquals("sync-api: a 302 is not followed", false, runBlocking { realProbe(redirecting.address.port).isUp() })
        } finally {
            redirecting.stop(0)
            target.stop(0)
        }
    }

    @Test(timeout = 20_000)
    fun `an answer of 201 or 204 is down, only 200 is up`() {
        for (code in listOf(201, 204)) {
            val server = serverAnswering(code)
            try {
                assertEquals("sync-api: $code is down", false, runBlocking { realProbe(server.address.port).isUp() })
            } finally {
                server.stop(0)
            }
        }
    }

    @Test(timeout = 30_000)
    fun `a server that sleeps 2500 ms is down well before it answers`() {
        val server = serverAnswering(200, sleepMs = 2_500L)
        try {
            val startedNs = System.nanoTime()
            val up = runBlocking { realProbe(server.address.port).isUp() }
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            assertFalse("sync-api: a sleeping server is down", up)
            assertTrue("sync-api: down within 2200 ms, took $elapsedMs ms", elapsedMs < 2_200)
        } finally {
            server.stop(0)
        }
    }

    // ---- Source pins of the real probe (Gradle runs tests with the module folder as the working directory).

    private fun probeSource(): String {
        val file = File("src/main/kotlin/dev/breaker/server/syncapi/status/StatusProbe.kt")
        assertTrue("sync-api: the source file must exist: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `the probe pins one second for the connection and one second for the answer`() {
        val source = probeSource()
        assertEquals("sync-api: the connect timeout line", 1, Regex("""\.connectTimeout\(Duration\.ofSeconds\(1\)\)""").findAll(source).count())
        assertEquals("sync-api: the request timeout call", 1, Regex("""\.timeout\(Duration\.ofSeconds\(1\)\)""").findAll(source).count())
    }

    @Test
    fun `the probe never follows a redirect and never reads the body`() {
        val source = probeSource()
        assertTrue("sync-api: redirects are never followed", source.contains("followRedirects(HttpClient.Redirect.NEVER)"))
        assertTrue("sync-api: the body is discarded", source.contains("HttpResponse.BodyHandlers.discarding()"))
        for (reading in listOf("ofString", "ofInputStream", "ofByteArray", "ofLines", "ofFile")) {
            assertFalse("sync-api: the probe must not use BodyHandlers.$reading", source.contains("BodyHandlers.$reading"))
        }
    }
}
