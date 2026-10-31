package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.buildTranscriptionApi
import dev.breaker.server.whisper.config.WhisperConfig
import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.MutableClock
import dev.breaker.server.whisper.formatUptime
import dev.breaker.server.whisper.sanitiseForwardingTo
import dev.breaker.server.whisper.startupHealth
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
 * The GET /health route and the startup seams that feed it (sanitiseForwardingTo, formatUptime,
 * uptimeProvider, startupHealth, buildTranscriptionApi). Uptime is driven by an injected monotonic
 * source (System::nanoTime in production), never a wall clock, so it advances between requests and
 * never runs backwards.
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

    /** Installs the transcription routes with explicit health params (the shared fixture cannot set them). */
    private fun withHealthApi(
        store: JobStore,
        forwardingTo: String = "",
        uptime: () -> String = { "0h0m0s" },
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) {
        testApplication {
            application { TranscriptionApi(store, {}, forwardingTo = forwardingTo, uptime = uptime).install(this) }
            block(client)
        }
    }

    @Test
    fun `GET health returns 200 with a json body of the health shape`() {
        val store = openStore()
        withHealthApi(store, forwardingTo = "http://whisper-x:9000", uptime = { "0h0m0s" }) { client ->
            val response = client.get("/health")
            assertEquals(200, response.status.value)
            val contentType = response.headers[HttpHeaders.ContentType]
            assertTrue("content-type was $contentType", contentType != null && contentType.contains("application/json"))
            assertEquals(
                """{"status":"ok","forwarding_to":"http://whisper-x:9000","uptime":"0h0m0s"}""",
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
    fun `buildTranscriptionApi sanitises the forwarding URL and drives uptime from the monotonic source`() {
        val store = openStore()
        val manual = ManualNanos()
        val config = WhisperConfig(
            bindAddress = "127.0.0.1",
            port = 8080,
            databasePath = "/tmp/whisper-build-health.db",
            forwarderBaseUrl = "http://user:SECRET@whisper-x:9000/v1/tok?api_key=KEY123",
        )
        val api = buildTranscriptionApi(store, {}, config, { manual() })
        testApplication {
            application { api.install(this) }
            assertEquals(
                """{"status":"ok","forwarding_to":"http://whisper-x:9000","uptime":"0h0m0s"}""",
                client.get("/health").bodyAsText(),
            )
            manual.advanceBy(3_000_000_000L) // advance the monotonic source 3s
            assertEquals(
                """{"status":"ok","forwarding_to":"http://whisper-x:9000","uptime":"0h0m3s"}""",
                client.get("/health").bodyAsText(),
            )
            val body = client.get("/health").bodyAsText()
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
                """{"status":"ok","forwarding_to":"","uptime":"0h0m0s"}""",
                client.get("/health").bodyAsText(),
            )
            manual.advanceBy(1_000_000_000L) // 1s
            assertEquals(
                """{"status":"ok","forwarding_to":"","uptime":"0h0m1s"}""",
                client.get("/health").bodyAsText(),
            )
        }
    }

    @Test
    fun `startupHealth wires scheme host and port only with no secret or path token`() {
        val store = openStore()
        val manual = ManualNanos()
        val config = WhisperConfig(
            bindAddress = "127.0.0.1",
            port = 8080,
            databasePath = "/tmp/whisper-health-test.db",
            forwarderBaseUrl = "http://user:SECRET@whisper-x:9000/v1/tok?api_key=KEY123",
        )
        val (forwardingTo, uptime) = startupHealth(config, { manual() })
        withHealthApi(store, forwardingTo = forwardingTo, uptime = uptime) { client ->
            val body = client.get("/health").bodyAsText()
            assertEquals(
                """{"status":"ok","forwarding_to":"http://whisper-x:9000","uptime":"0h0m0s"}""",
                body,
            )
            for (secret in listOf("SECRET", "KEY123", "user", "@", "/v1/tok", "api_key")) {
                assertTrue("health body leaked '$secret': $body", secret !in body)
            }
        }
    }

    @Test
    fun `sanitiseForwardingTo keeps scheme host and port only`() {
        assertEquals("http://h:9000", sanitiseForwardingTo("http://u:p@h:9000/t?k=v#f"))
        assertEquals("https://h", sanitiseForwardingTo("https://h"))
        assertEquals("http://[::1]:9000", sanitiseForwardingTo("http://[::1]:9000"))
        assertEquals("http://h:9000", sanitiseForwardingTo("http://user:p@ss@h:9000"))
        assertEquals("http://h:9000", sanitiseForwardingTo("http://h:9000?k=v")) // query dropped
        assertEquals("http://h:9000", sanitiseForwardingTo("http://h:9000#f")) // fragment dropped
        assertEquals("unparseable", sanitiseForwardingTo("ftp://h:9000")) // non-http scheme
        assertEquals("unparseable", sanitiseForwardingTo("file:///x")) // non-http scheme
        assertEquals("unparseable", sanitiseForwardingTo("http://:9000")) // empty host
        assertEquals("unparseable", sanitiseForwardingTo("whisper-x:9000"))
        assertEquals("unparseable", sanitiseForwardingTo(""))
        assertEquals("unparseable", sanitiseForwardingTo("://bad"))
        assertEquals("unparseable", sanitiseForwardingTo("http://"))
        assertEquals("unparseable", sanitiseForwardingTo("http://user:SECRET"))
        assertEquals("unparseable", sanitiseForwardingTo("http://h:abc"))
        assertEquals("unparseable", sanitiseForwardingTo("http://[::1]:abc"))
        assertEquals("unparseable", sanitiseForwardingTo("http://h:"))
        assertEquals("unparseable", sanitiseForwardingTo("http://h:99999"))
    }

    @Test
    fun `health route escapes a quote and backslash injected into forwardingTo`() {
        val store = openStore()
        // A double quote and a backslash that would break the JSON body if left unescaped.
        val raw = "he\"llo\\world"
        withHealthApi(store, forwardingTo = raw) { client ->
            val body = client.get("/health").bodyAsText()
            assertTrue("body must escape the quote: $body", body.contains("\\\""))
            assertTrue("body must escape the backslash: $body", body.contains("\\\\"))
            // Valid escaped JSON that parses back to the exact string that was injected.
            assertEquals(raw, decodeJsonValue(body, "forwarding_to"))
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

/**
 * Minimal JSON string decoder: scans the value of [key] in a flat {"k":"v",...} body, honouring
 * \" \\ \n \r \t and \uXXXX, and returns the unescaped value. Used to prove the /health body is
 * valid escaped JSON that parses back to the exact string it carried.
 */
private fun decodeJsonValue(body: String, key: String): String {
    val marker = "\"$key\":"
    var i = body.indexOf(marker)
    check(i >= 0) { "key $key missing from $body" }
    i += marker.length
    check(body[i] == '"') { "expected opening quote for $key in $body" }
    i++
    val sb = StringBuilder()
    while (i < body.length) {
        val c = body[i]
        when {
            c == '"' -> return sb.toString()
            c == '\\' -> {
                val e = body[i + 1]
                when (e) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        sb.append(body.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i += 2
            }
            else -> { sb.append(c); i++ }
        }
    }
    error("unterminated JSON string for $key in $body")
}
