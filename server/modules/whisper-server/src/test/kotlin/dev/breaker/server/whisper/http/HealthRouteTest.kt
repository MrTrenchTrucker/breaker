package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.buildTranscriptionApi
import dev.breaker.server.whisper.config.WhisperConfig
import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import dev.breaker.server.whisper.formatUptime
import dev.breaker.server.whisper.startupUptime
import dev.breaker.server.whisper.uptimeProvider
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GET /health route and the startup seams that feed it (formatUptime, uptimeProvider,
 * startupUptime, buildTranscriptionApi). Uptime is driven by an injected monotonic source
 * (System::nanoTime in production), never a wall clock, so it advances between requests and never
 * runs backwards. The route answers status and uptime only: the forwarder URL is never computed,
 * passed or echoed, so no host, port or forwarding member may appear in the body.
 */
internal class HealthRouteTest {

    private val opened = ArrayList<TempDatabase>()

    private fun openStore(): JobStore {
        val temp = TestDatabases.openTemp()
        opened.add(temp)
        return JobFixtures.store(temp, MutableClock(JobFixtures.START))
    }

    @After
    fun closeDatabases() {
        for (temp in opened) {
            temp.close()
        }
    }

    /** Installs the transcription routes with an explicit uptime provider (the shared fixture cannot set it). */
    private fun withHealthApi(
        store: JobStore,
        uptime: () -> String = { "0h0m0s" },
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) {
        testApplication {
            application { TranscriptionApi(store, {}, uptime = uptime).install(this) }
            block(client)
        }
    }

    @Test
    fun `GET health returns 200 with a json body of the health shape`() {
        val store = openStore()
        withHealthApi(store, uptime = { "0h0m0s" }) { client ->
            val response = client.get("/health")
            assertEquals(200, response.status.value)
            val contentType = response.headers[HttpHeaders.ContentType]
            assertTrue("content-type was $contentType", contentType != null && contentType.contains("application/json"))
            assertEquals(
                """{"status":"ok","uptime":"0h0m0s"}""",
                response.bodyAsText(),
            )
        }
    }

    @Test
    fun `GET health needs no auth header`() {
        val store = openStore()
        withHealthApi(store) { client ->
            val response = client.get("/health")
            assertEquals(200, response.status.value)
        }
    }

    @Test
    fun `buildTranscriptionApi drives uptime from the monotonic source and never echoes the forwarder`() {
        val store = openStore()
        val manual = ManualNanos()
        val config = WhisperConfig(
            bindAddress = "127.0.0.1",
            port = 8080,
            databasePath = "/tmp/whisper-build-health.db",
            forwarderBaseUrl = "http://user:SECRET@forwarder-absence.invalid:61637/v1/tok?api_key=KEY123",
            syncApiBaseUrl = "http://sync-api:8081",
            syncApiServiceKey = "svc-key",
        )
        val api = buildTranscriptionApi(store, {}, config, { manual() })
        testApplication {
            application { api.install(this) }
            assertEquals(
                """{"status":"ok","uptime":"0h0m0s"}""",
                client.get("/health").bodyAsText(),
            )
            manual.advanceBy(3_000_000_000L) // advance the monotonic source 3s
            assertEquals(
                """{"status":"ok","uptime":"0h0m3s"}""",
                client.get("/health").bodyAsText(),
            )
            val body = client.get("/health").bodyAsText()
            assertTrue("health body still writes the forwarding member: $body", "forwarding_to" !in body)
            assertTrue("health body echoed the forwarder host: $body", "forwarder-absence.invalid" !in body)
            assertTrue("health body echoed the forwarder port: $body", "61637" !in body)
            for (secret in listOf("SECRET", "KEY123", "user", "@", "/v1/tok", "api_key")) {
                assertTrue("health body leaked '$secret': $body", secret !in body)
            }
        }
    }

    @Test
    fun `uptime advances across two requests via the real uptimeProvider`() {
        val store = openStore()
        val manual = ManualNanos()
        val provider = uptimeProvider { manual() }
        withHealthApi(store, uptime = provider) { client ->
            assertEquals(
                """{"status":"ok","uptime":"0h0m0s"}""",
                client.get("/health").bodyAsText(),
            )
            manual.advanceBy(1_000_000_000L) // 1s
            assertEquals(
                """{"status":"ok","uptime":"0h0m1s"}""",
                client.get("/health").bodyAsText(),
            )
        }
    }

    @Test
    fun `startupUptime drives the uptime provider from the monotonic source`() {
        val store = openStore()
        val manual = ManualNanos()
        val uptime = startupUptime { manual() }
        withHealthApi(store, uptime = uptime) { client ->
            val body = client.get("/health").bodyAsText()
            assertEquals("""{"status":"ok","uptime":"0h0m0s"}""", body)
            manual.advanceBy(2_000_000_000L) // 2s
            val later = client.get("/health").bodyAsText()
            assertEquals("""{"status":"ok","uptime":"0h0m2s"}""", later)
            // startupUptime reads no configuration, so the forwarder URL is nowhere involved: the
            // exact-body equality above proves no forwarding member is echoed.
        }
    }

    @Test
    fun `uptimeProvider renders elapsed from the monotonic source`() {
        val manual = ManualNanos()
        val provider = uptimeProvider { manual() }
        assertEquals("0h0m0s", provider())
        manual.advanceBy(3_000_000_000L) // 3s
        assertEquals("0h0m3s", provider())
    }

    @Test
    fun `uptimeProvider holds when a wall clock elsewhere is stepped back`() {
        val manual = ManualNanos()
        // The provider is built from the monotonic source alone; it is given no wall clock at all.
        val provider = uptimeProvider { manual() }
        assertEquals("0h0m0s", provider())
        manual.advanceBy(5_000_000_000L) // 5s of monotonic time
        val second = provider()
        assertEquals("0h0m5s", second)
        // A wall clock used elsewhere is stepped back an hour; the monotonic uptime must not move.
        val wall = MutableClock(JobFixtures.START)
        wall.set(JobFixtures.START.minusSeconds(36_000))
        assertTrue("the fake wall clock should read earlier now", wall.instant() < JobFixtures.START)
        manual.advanceBy(1_000_000_000L) // 6s total monotonic
        val third = provider()
        assertTrue("uptime went backwards: $second -> $third", second <= third)
        assertEquals("0h0m6s", third)
        assertTrue("uptime rendered negative: $third", third.none { c -> c == '-' })
    }

    @Test
    fun `the first uptime reading right after startup is zero`() {
        val manual = ManualNanos()
        val provider = uptimeProvider { manual() }
        assertEquals("0h0m0s", provider())
    }

    @Test
    fun `formatUptime renders a duration as HhMmSs`() {
        assertEquals("0h0m0s", formatUptime(Duration.ZERO))
        assertEquals("1h2m3s", formatUptime(Duration.ofSeconds(3723)))
        assertEquals("1h1m1s", formatUptime(Duration.ofSeconds(3661)))
    }
}

/** A monotonic nanosecond source the test advances by hand. Single-coroutine use only. */
private class ManualNanos(var nanos: Long = 7_000_000_000L) {
    operator fun invoke(): Long = nanos
    fun advanceBy(delta: Long) { nanos += delta }
}
