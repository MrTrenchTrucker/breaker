package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The API key stays out of every failure text, every recorded request except the Authorization header,
 * and every refusal path. The key is a canary value: a check that finds it fails with a fixed message only.
 */
class KeySecrecyTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val BOUNDARY = "0123456789abcdef"
    }

    @Test
    fun `a refused key on enqueue leaves the key out of the failure text`() {
        val (result, _) = once(listOf(reply(401, "refused $KEY")))
        assertTrue("failure text carries no key", !detailOf(result).contains(KEY))
    }

    @Test
    fun `a transport error message holding the key does not reach the failure text`() {
        val (result, _) = once(listOf(ScriptedHttp.Step.Throw(IOException("header value $KEY"))))
        assertFixedFailure(result, SttError.SERVER_UNREACHABLE, FailureSentences.CONNECTION_LOST)
    }

    @Test
    fun `an unexpected transport exception holding the key does not reach the failure text`() {
        val (result, _) = once(listOf(ScriptedHttp.Step.Throw(RuntimeException("raw $KEY"))))
        assertFixedFailure(result, SttError.OTHER, FailureSentences.UNEXPECTED)
    }

    @Test
    fun `a failed job text holding the key is cleaned before it reaches the failure text`() {
        val failed = "{\"status\":\"failed\",\"error\":\"job failed near $KEY end\"}"
        val (result, _) = once(listOf(reply(202, accepted()), reply(200, failed), reply(200, done())))
        assertFixedFailure(result, SttError.OTHER, FailureSentences.JOB_FAILED_PREFIX + "job failed near  end")
    }

    @Test
    fun `a bad answer body holding the key does not reach the failure text`() {
        val (result, _) = once(listOf(reply(202, "not json $KEY")))
        assertFixedFailure(result, SttError.OTHER, FailureSentences.BAD_ANSWER)
    }

    @Test
    fun `the key reaches the wire only in the authorization header of every request`() {
        val (_, sent) = once(listOf(reply(202, accepted()), reply(200, done())))
        assertEquals(2, sent.size)
        for (request in sent) {
            assertTrue("the url carries no key", !request.url.contains(KEY))
            assertTrue("the body carries no key", !(request.body?.toString(Charsets.UTF_8) ?: "").contains(KEY))
            assertTrue("the request text carries no key", !request.toString().contains(KEY))
            for ((name, value) in request.headers) {
                if (name != "Authorization") assertTrue("only the authorization header carries the key", !value.contains(KEY))
            }
            assertTrue("the authorization header carries the key", request.headers["Authorization"] == "Bearer $KEY")
        }
    }

    @Test
    fun `a plain http address is refused and nothing is sent`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, done())), ft.clock)
        val result = engineFor(http, ft.timing, base = "http://example.test:8080").transcribe(request())
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.ADDRESS_NOT_HTTPS), result)
        assertTrue("nothing was sent to the plain address", http.requests.isEmpty())
    }

    @Test
    fun `a key with a line break is refused and nothing is sent`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, done())), ft.clock)
        val result = engineFor(http, ft.timing, key = "canary\nkey 7741").transcribe(request())
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.KEY_NOT_USABLE), result)
        assertTrue("nothing was sent with the unusable key", http.requests.isEmpty())
    }

    /** Checks with a fixed message only: an assertEquals here would print the key-bearing text on failure. */
    private fun assertFixedFailure(result: SttResult, error: SttError, sentence: String) {
        assertTrue("the failure is the fixed sentence", result == SttResult.Failure(error, sentence))
    }

    private fun detailOf(result: SttResult): String = (result as? SttResult.Failure)?.detail ?: ""

    private fun once(steps: List<ScriptedHttp.Step>): Pair<SttResult, List<ServerRequest>> {
        val ft = FakeTiming()
        val http = ScriptedHttp(steps, ft.clock)
        val result = engineFor(http, ft.timing).transcribe(request())
        return Pair(result, http.requests)
    }

    private fun request(): SttRequest = SttRequest(FloatArray(16_000), byteArrayOf(1, 2, 3), "small", "en")

    private fun reply(status: Int, body: String) = ScriptedHttp.Step.Reply(status, body)

    private fun engineFor(
        http: ServerHttp,
        timing: Timing,
        base: String = BASE,
        key: String = KEY,
    ): SttServerEngine = SttServerEngine(
        serverUrl = { base },
        apiKey = { key },
        limits = ServerLimits(),
        http = http,
        timing = timing,
        boundary = BoundarySource { BOUNDARY },
    )

    private fun accepted(): String = "{\"job_id\":\"job-1\",\"status\":\"queued\"}"

    private fun done(): String =
        "{\"status\":\"done\",\"result\":{\"text\":\"hello\",\"language\":\"en\",\"segments\":[]}}"
}
