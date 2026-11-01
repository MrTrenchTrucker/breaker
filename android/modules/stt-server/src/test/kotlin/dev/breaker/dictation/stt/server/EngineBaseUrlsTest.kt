package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine sends only to the address it was given. For an http base and an https base, the post URL
 * and the job URL both start with the base, and no other request reaches the scripted transport. The
 * engine's configured address is its single provider, so "only the configured server" is shown by
 * this: every URL it calls is that base plus a fixed path.
 */
class EngineBaseUrlsTest {

    private val sampleValue = "sample value"

    /** A boundary of 16 lower-case hex characters, built so no long run is written out. */
    private val boundaryText = "ab".repeat(8)

    @Test
    fun `every url the engine calls starts with an http base and nothing else reaches the transport`() {
        assertCallsStayOnBase("http://example.test:8080")
    }

    @Test
    fun `every url the engine calls starts with an https base and nothing else reaches the transport`() {
        assertCallsStayOnBase("https://example.test:8443")
    }

    @Test
    fun `a base with a path keeps the path in front of both urls whether or not it ends in a slash`() {
        for (base in listOf("http://example.test:8080/prefix/", "https://example.test/prefix", "http://example.test/")) {
            assertCallsStayOnBase(base)
        }
    }

    @Test
    fun `the base is read again on every call so the urls follow a changed address`() {
        var base = "http://example.test:8080"
        val ft = FakeTiming()
        val steps = listOf(reply(202, accepted()), reply(200, done()), reply(202, accepted()), reply(200, done()))
        val http = ScriptedHttp(steps, ft.clock)
        val engine = engineFor(http, ft.timing) { base }

        assertTrue("the first call finished", engine.transcribe(request()) is SttResult.Success)
        assertEquals("two calls were made by the first call", 2, http.requests.size)
        base = "https://other.test:8443"
        assertTrue("the second call finished", engine.transcribe(request()) is SttResult.Success)

        assertEquals("four calls were made in all", 4, http.requests.size)
        assertEquals(
            listOf(
                "http://example.test:8080/v1/audio/transcriptions",
                "http://example.test:8080/v1/jobs/job-1",
                "https://other.test:8443/v1/audio/transcriptions",
                "https://other.test:8443/v1/jobs/job-1",
            ),
            http.requests.map { it.url },
        )
    }

    @Test
    fun `an address of another scheme sends nothing at all`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, done())), ft.clock)
        val result = engineFor(http, ft.timing) { "ftp://example.test:8080" }.transcribe(request())
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.ADDRESS_NOT_HTTPS), result)
        assertTrue("nothing was sent to an address of another scheme", http.requests.isEmpty())
    }

    private fun assertCallsStayOnBase(base: String) {
        val expectedBase = base.removeSuffix("/")
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, done())), ft.clock)
        val result = engineFor(http, ft.timing) { base }.transcribe(request())

        assertTrue("the call finished with a result for $base", result is SttResult.Success)
        assertEquals("exactly two calls were made for $base", 2, http.requests.size)
        assertEquals("POST", http.requests[0].method)
        assertEquals("GET", http.requests[1].method)
        assertEquals("$expectedBase/v1/audio/transcriptions", http.requests[0].url)
        assertEquals("$expectedBase/v1/jobs/job-1", http.requests[1].url)
        for (sent in http.requests) {
            assertTrue("a url does not start with the base: ${sent.url}", sent.url.startsWith("$expectedBase/"))
        }
    }

    private fun request(): SttRequest = SttRequest(FloatArray(16_000), byteArrayOf(1, 2, 3), "small", "en")

    private fun reply(status: Int, body: String) = ScriptedHttp.Step.Reply(status, body)

    private fun engineFor(http: ServerHttp, timing: Timing, base: () -> String): SttServerEngine = SttServerEngine(
        serverUrl = base,
        apiKey = { sampleValue },
        limits = ServerLimits(),
        http = http,
        timing = timing,
        boundary = BoundarySource { boundaryText },
    )

    private fun accepted(): String = "{\"job_id\":\"job-1\",\"status\":\"queued\"}"

    private fun done(): String =
        "{\"status\":\"done\",\"result\":{\"text\":\"hello\",\"language\":\"en\",\"segments\":[]}}"
}
