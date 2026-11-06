package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The engine reads the address and the key of a call once, together, and uses that one pair throughout. */
class EngineTargetReadTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val FIRST_BASE = "https://first.test:8443"
        const val SECOND_BASE = "https://second.test:8443"
        const val FIRST_KEY = "first canary key"
        const val SECOND_KEY = "second canary key"
        val BOUNDARY: String = "ab".repeat(8)
    }

    @Test
    fun `one transcribe with an enqueue and three polls reads the provider exactly once`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(fullScript(), ft.clock)
        val reads = AtomicInteger(0)
        val provider = { reads.incrementAndGet(); ServerTarget(BASE, KEY) }
        val result = engineFor(http, ft.timing, provider).transcribe(request())
        assertTrue("the call finished with a success", result is SttResult.Success)
        assertEquals("one enqueue and four polls were sent", 5, http.requests.size)
        assertEquals("the provider was read once for the whole call", 1, reads.get())
    }

    @Test
    fun `a provider that gives a different pair on each call is used as its first pair on every request`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(fullScript(), ft.clock)
        val pairs = listOf(ServerTarget(FIRST_BASE, FIRST_KEY), ServerTarget(SECOND_BASE, SECOND_KEY))
        val reads = AtomicInteger(0)
        val provider = { pairs[minOf(reads.getAndIncrement(), pairs.size - 1)] }
        val result = engineFor(http, ft.timing, provider).transcribe(request())
        assertTrue("the call finished with a success", result is SttResult.Success)
        assertEquals("one enqueue and four polls were sent", 5, http.requests.size)
        for (sent in http.requests) {
            assertTrue("the url belongs to the first base: ${sent.url}", sent.url.startsWith("$FIRST_BASE/"))
            assertTrue("the url holds nothing of the second base", !sent.url.contains("second.test"))
            assertTrue(
                "the bearer of the ${sent.method} is the first key",
                sent.headers["Authorization"] == "Bearer $FIRST_KEY",
            )
        }
        assertEquals("the provider was read once", 1, reads.get())
    }

    @Test
    fun `a null key gives the no key sentence and sends nothing`() {
        assertRefusedBeforeSending(ServerTarget(BASE, null), FailureSentences.NO_KEY)
    }

    @Test
    fun `a blank key gives the no key sentence and sends nothing`() {
        assertRefusedBeforeSending(ServerTarget(BASE, "   "), FailureSentences.NO_KEY)
    }

    @Test
    fun `an unusable base gives the address sentence and sends nothing`() {
        val target = ServerTarget("ftp://example.test", KEY)
        assertRefusedBeforeSending(target, FailureSentences.ADDRESS_NOT_HTTPS)
    }

    @Test
    fun `a key with a line break gives the key not usable sentence and sends nothing`() {
        assertRefusedBeforeSending(ServerTarget(BASE, "canary\nkey 7741"), FailureSentences.KEY_NOT_USABLE)
    }

    @Test
    fun `a refused key on the enqueue gives the key refused sentence without the key or the base`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(401, "refused $KEY at $BASE")), ft.clock)
        val result = engineFor(http, ft.timing) { ServerTarget(BASE, KEY) }.transcribe(request())
        val refused = SttResult.Failure(SttError.OTHER, FailureSentences.KEY_REFUSED)
        assertTrue("the failure is the key refused sentence", result == refused)
        val detail = (result as? SttResult.Failure)?.detail ?: ""
        assertTrue("the failure text holds no key", !detail.contains(KEY))
        assertTrue("the failure text holds no base", !detail.contains("example.test"))
    }

    @Test
    fun `a failed job whose error text holds the key is cleaned of the key`() {
        val ft = FakeTiming()
        val failed = "{\"status\":\"failed\",\"error\":\"job failed near $KEY end\"}"
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, failed)), ft.clock)
        val result = engineFor(http, ft.timing) { ServerTarget(BASE, KEY) }.transcribe(request())
        val cleaned = FailureSentences.JOB_FAILED_PREFIX + "job failed near  end"
        assertTrue(
            "the failure text is the cleaned job sentence",
            result == SttResult.Failure(SttError.OTHER, cleaned),
        )
    }

    @Test
    fun `the two provider form gives the old sentence and never reads the key for an unusable base`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf<ScriptedHttp.Step>(), ft.clock)
        var keyReads = 0
        val engine = oldShapeEngine(http, ft.timing, { "ftp://example.test" }, { keyReads++; KEY })
        val result = engine.transcribe(request())
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.ADDRESS_NOT_HTTPS), result)
        assertEquals("the key provider was never called", 0, keyReads)
        assertTrue("nothing was sent", http.requests.isEmpty())
    }

    @Test
    fun `the two provider form reads the key once per transcribe for a usable base`() {
        val ft = FakeTiming()
        val one = listOf(reply(202, accepted()), reply(200, done()))
        val http = ScriptedHttp(one + one, ft.clock)
        var keyReads = 0
        val engine = oldShapeEngine(http, ft.timing, { BASE }, { keyReads++; KEY })
        assertTrue("the first call finished", engine.transcribe(request()) is SttResult.Success)
        assertEquals("the key was read once by the first call", 1, keyReads)
        assertTrue("the second call finished", engine.transcribe(request()) is SttResult.Success)
        assertEquals("the key was read again by the second call", 2, keyReads)
        for (sent in http.requests) {
            assertTrue("every request carries the key", sent.headers["Authorization"] == "Bearer $KEY")
        }
    }

    @Test
    fun `the two provider form gives the no key sentence for a null key`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf<ScriptedHttp.Step>(), ft.clock)
        val result = oldShapeEngine(http, ft.timing, { BASE }, { null }).transcribe(request())
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.NO_KEY), result)
        assertTrue("nothing was sent", http.requests.isEmpty())
    }

    @Test
    fun `a provider that throws gives the unexpected failure and does not throw`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf<ScriptedHttp.Step>(), ft.clock)
        val failing = { throw IllegalStateException("target source failed") }
        val result = engineFor(http, ft.timing, failing).transcribe(request())
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.UNEXPECTED), result)
        assertTrue("nothing was sent", http.requests.isEmpty())
    }

    private fun assertRefusedBeforeSending(target: ServerTarget, sentence: String) {
        val ft = FakeTiming()
        val http = ScriptedHttp(fullScript(), ft.clock)
        val result = engineFor(http, ft.timing) { target }.transcribe(request())
        assertEquals(SttResult.Failure(SttError.OTHER, sentence), result)
        assertTrue("nothing was sent", http.requests.isEmpty())
    }

    private fun fullScript(): List<ScriptedHttp.Step> = listOf(
        reply(202, accepted()),
        reply(200, "{\"status\":\"queued\"}"),
        reply(200, "{\"status\":\"processing\"}"),
        reply(200, "{\"status\":\"processing\"}"),
        reply(200, done()),
    )

    private fun request(): SttRequest = SttRequest(FloatArray(16_000), byteArrayOf(1, 2, 3), "small", "en")

    private fun reply(status: Int, body: String) = ScriptedHttp.Step.Reply(status, body)

    private fun engineFor(http: ServerHttp, timing: Timing, provider: () -> ServerTarget): SttServerEngine =
        SttServerEngine(
            target = provider,
            limits = ServerLimits(),
            http = http,
            timing = timing,
            boundary = BoundarySource { BOUNDARY },
        )

    private fun oldShapeEngine(
        http: ServerHttp,
        timing: Timing,
        base: () -> String,
        key: () -> String?,
    ): SttServerEngine = SttServerEngine(
        serverUrl = base,
        apiKey = key,
        limits = ServerLimits(),
        http = http,
        timing = timing,
        boundary = BoundarySource { BOUNDARY },
    )

    private fun accepted(): String = "{\"job_id\":\"job-1\",\"status\":\"queued\"}"

    private fun done(): String =
        "{\"status\":\"done\",\"result\":{\"text\":\"hello\",\"language\":\"en\",\"segments\":[]}}"
}
