package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** The poll loop: every poll status row, every bad poll answer, and the transient failure counter. */
class PollFailureTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val BOUNDARY = "0123456789abcdef"
    }

    @Test
    fun `a poll refused for its key is a key refused failure`() =
        assertPollFailure(SttError.OTHER, FailureSentences.KEY_REFUSED, reply(401, ""))

    @Test
    fun `a poll refused with 403 is a key refused failure`() =
        assertPollFailure(SttError.OTHER, FailureSentences.KEY_REFUSED, reply(403, ""))

    @Test
    fun `a poll for a job the server no longer knows is a job gone failure`() =
        assertPollFailure(SttError.OTHER, FailureSentences.JOB_GONE, reply(404, ""))

    @Test
    fun `an unlisted poll status is an unexpected status failure`() =
        assertPollFailure(SttError.OTHER, FailureSentences.UNEXPECTED_STATUS, reply(418, ""))

    @Test
    fun `a poll answer that is not JSON is a bad answer`() =
        assertPollFailure(SttError.OTHER, FailureSentences.BAD_ANSWER, reply(200, "not json"))

    @Test
    fun `a poll answer with an unknown status is a bad answer and is not polled again`() =
        assertPollFailure(SttError.OTHER, FailureSentences.BAD_ANSWER, reply(200, "{\"status\":\"weird\"}"))

    @Test
    fun `a done poll without a result is a bad answer and not a success`() =
        assertPollFailure(SttError.OTHER, FailureSentences.BAD_ANSWER, reply(200, "{\"status\":\"done\"}"))

    @Test
    fun `a poll answer with trailing text is a bad answer`() =
        assertPollFailure(SttError.OTHER, FailureSentences.BAD_ANSWER, reply(200, "{\"status\":\"queued\"} x"))

    @Test
    fun `a poll answer nested deeper than the reader allows is a bad answer`() =
        assertPollFailure(SttError.OTHER, FailureSentences.BAD_ANSWER, reply(200, deepJson()))

    @Test
    fun `a job that fails gives a failure with the cleaned server text and is not polled again`() {
        val (result, sent) = once(afterAccept(reply(200, "{\"status\":\"failed\",\"error\":\"boom\"}"), reply(200, done())))
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.JOB_FAILED_PREFIX + "boom"), result)
        assertEquals(2, sent.size)
        assertTrue("the poll carries the key", sent[1].headers["Authorization"] == "Bearer $KEY")
    }

    @Test
    fun `three consecutive transient poll failures are tolerated`() {
        val (result, _) = once(afterAccept(reply(503, ""), reply(503, ""), reply(503, ""), reply(200, done())))
        assertTrue("the answer after three failures is read", result is SttResult.Success)
    }

    @Test
    fun `a fourth consecutive transient poll failure ends the call`() =
        assertPollFailure(
            SttError.SERVER_UNREACHABLE,
            FailureSentences.NOT_TAKING_WORK,
            reply(503, ""), reply(503, ""), reply(503, ""), reply(503, ""),
        )

    @Test
    fun `a good poll resets the count of consecutive transient failures`() {
        val (result, _) = once(
            afterAccept(
                reply(503, ""), reply(503, ""), reply(200, "{\"status\":\"queued\"}"),
                reply(503, ""), reply(503, ""), reply(503, ""), reply(200, done()),
            ),
        )
        assertTrue("the call reads the answer after the reset", result is SttResult.Success)
    }

    @Test
    fun `the last failure sentence ends the call once the tolerated failures are used up`() =
        assertPollFailure(
            SttError.SERVER_UNREACHABLE,
            FailureSentences.CONNECTION_LOST,
            reply(503, ""), reply(503, ""), reply(503, ""), ScriptedHttp.Step.Throw(IOException("lost")),
        )

    @Test
    fun `a read timeout on a poll ends the call at once when no failure is tolerated`() {
        val steps = afterAccept(ScriptedHttp.Step.Throw(SocketTimeoutException("slow")), reply(200, done()))
        val (result, sent) = once(steps, ServerLimits(maxTransientPollFailures = 0))
        assertEquals(SttResult.Failure(SttError.TIMEOUT, FailureSentences.NO_ANSWER_IN_TIME), result)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a 429 on a poll ends the call at once when no failure is tolerated`() {
        val (result, sent) = once(afterAccept(reply(429, ""), reply(200, done())), ServerLimits(maxTransientPollFailures = 0))
        assertEquals(SttResult.Failure(SttError.SERVER_UNREACHABLE, FailureSentences.NOT_TAKING_WORK), result)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a TLS failure on a poll is reported at once and never retried`() =
        assertPollFailure(SttError.SERVER_UNREACHABLE, FailureSentences.TLS_FAILED, ScriptedHttp.Step.Throw(SSLException("tls")))

    @Test
    fun `a poll that throws a TLS connect failure ends at once with the TLS sentence`() =
        assertPollFailure(
            SttError.SERVER_UNREACHABLE,
            FailureSentences.TLS_FAILED,
            ScriptedHttp.Step.Throw(ConnectFailedException(SSLHandshakeException("h"))),
        )

    @Test
    fun `a poll that throws a copied TLS connect failure ends at once with the TLS sentence`() =
        assertPollFailure(
            SttError.SERVER_UNREACHABLE,
            FailureSentences.TLS_FAILED,
            ScriptedHttp.Step.Throw(ConnectFailedException(ConnectFailedException(SSLHandshakeException("h")))),
        )

    @Test
    fun `a job id that is not usable is never polled`() {
        val (result, sent) = once(listOf(reply(202, accepted("../x")), reply(200, done())))
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.BAD_ANSWER), result)
        assertEquals(1, sent.size)
    }

    private fun assertPollFailure(error: SttError, sentence: String, vararg polls: ScriptedHttp.Step) {
        val (result, sent) = once(afterAccept(*polls, reply(200, done())))
        assertEquals(SttResult.Failure(error, sentence), result)
        assertEquals(polls.size + 1, sent.size)
    }

    private fun afterAccept(vararg polls: ScriptedHttp.Step): List<ScriptedHttp.Step> =
        listOf<ScriptedHttp.Step>(reply(202, accepted())) + polls.toList()

    private fun once(
        steps: List<ScriptedHttp.Step>,
        limits: ServerLimits = ServerLimits(),
    ): Pair<SttResult, List<ServerRequest>> {
        val ft = FakeTiming()
        val http = ScriptedHttp(steps, ft.clock)
        val result = engineFor(http, ft.timing, limits).transcribe(request())
        return Pair(result, http.requests)
    }

    private fun request(): SttRequest = SttRequest(FloatArray(16_000), byteArrayOf(1, 2, 3), "small", "en")

    private fun reply(status: Int, body: String) = ScriptedHttp.Step.Reply(status, body)

    private fun engineFor(http: ServerHttp, timing: Timing, limits: ServerLimits): SttServerEngine = SttServerEngine(
        serverUrl = { BASE },
        apiKey = { KEY },
        limits = limits,
        http = http,
        timing = timing,
        boundary = BoundarySource { BOUNDARY },
    )

    private fun accepted(id: String = "job-1"): String = "{\"job_id\":\"$id\",\"status\":\"queued\"}"

    private fun done(): String =
        "{\"status\":\"done\",\"result\":{\"text\":\"hello\",\"language\":\"en\",\"segments\":[]}}"

    private fun deepJson(): String = "{\"status\":\"queued\",\"x\":" + "[".repeat(17) + "]".repeat(17) + "}"
}
