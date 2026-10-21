package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.SttSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The happy path: one enqueue, polls in order, and the finished text returned as a success. */
class SuccessPathTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val BOUNDARY = "0123456789abcdef"
    }

    @Test
    fun `a job that goes queued processing then done gives its text segments in milliseconds and language`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(
            listOf(
                reply(202, accepted()),
                reply(200, "{\"status\":\"queued\"}"),
                reply(200, "{\"status\":\"processing\"}"),
                reply(200, done()),
            ),
            ft.clock,
        )
        val result = engineFor(http, ft.timing).transcribe(request())
        assertEquals(
            SttResult.Success(
                "hello world",
                listOf(SttSegment(0L, 500L, "hello"), SttSegment(500L, 1250L, "world")),
                "en",
            ),
            result,
        )
        assertEquals(4, http.requests.size)
        assertEquals(listOf(500L, 750L, 1125L), ft.sleeper.askedMs)
    }

    @Test
    fun `a done job with empty text is a success with empty text and no segments`() {
        val ft = FakeTiming()
        val body = "{\"status\":\"done\",\"result\":{\"text\":\"\",\"language\":\"en\",\"segments\":[]}}"
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, body)), ft.clock)
        assertEquals(
            SttResult.Success("", emptyList(), "en"),
            engineFor(http, ft.timing).transcribe(request()),
        )
    }

    @Test
    fun `the language of a finished job is the language of the success`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, done("hallo", "de"))), ft.clock)
        val result = engineFor(http, ft.timing).transcribe(request())
        assertTrue("language comes from the result", result is SttResult.Success && result.language == "de")
    }

    @Test
    fun `the enqueue call posts the audio once to the transcriptions address with the key only in its authorization header`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, done())), ft.clock)
        engineFor(http, ft.timing).transcribe(request())
        val post = http.requests[0]
        assertEquals("POST", post.method)
        assertEquals("$BASE/v1/audio/transcriptions", post.url)
        assertTrue("authorization header carries the key", post.headers["Authorization"] == "Bearer $KEY")
        assertEquals("application/json", post.headers["Accept"])
        assertEquals(1500, post.connectTimeoutMs)
        assertEquals(10_000, post.readTimeoutMs)
        val text = post.body?.toString(Charsets.UTF_8) ?: ""
        assertTrue("file part carries the wav name", text.contains("filename=\"audio.wav\""))
        assertTrue("model part value follows its header", text.contains("name=\"model\"\r\n\r\nsmall\r\n"))
        assertTrue("language part value follows its header", text.contains("name=\"language\"\r\n\r\nen\r\n"))
        assertEquals(1, http.requests.count { it.method == "POST" })
    }

    @Test
    fun `each poll is a get of the job address carrying the key and no body`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted("job-9")), reply(200, done())), ft.clock)
        engineFor(http, ft.timing).transcribe(request())
        val poll = http.requests[1]
        assertEquals("GET", poll.method)
        assertEquals("$BASE/v1/jobs/job-9", poll.url)
        assertTrue("poll carries the key", poll.headers["Authorization"] == "Bearer $KEY")
        assertTrue("poll has no body", poll.body == null)
    }

    private fun request(): SttRequest = SttRequest(FloatArray(16_000), byteArrayOf(1, 2, 3), "small", "en")

    private fun reply(status: Int, body: String) = ScriptedHttp.Step.Reply(status, body)

    private fun engineFor(http: ServerHttp, timing: Timing): SttServerEngine = SttServerEngine(
        serverUrl = { BASE },
        apiKey = { KEY },
        limits = ServerLimits(),
        http = http,
        timing = timing,
        boundary = BoundarySource { BOUNDARY },
    )

    private fun accepted(id: String = "job-1"): String = "{\"job_id\":\"$id\",\"status\":\"queued\"}"

    private fun done(text: String = "hello world", language: String = "en"): String =
        "{\"status\":\"done\",\"result\":{\"text\":\"$text\",\"language\":\"$language\"," +
            "\"segments\":[{\"start\":0.0,\"end\":0.5,\"text\":\"hello\"}," +
            "{\"start\":0.5,\"end\":1.25,\"text\":\"world\"}]}}"
}
