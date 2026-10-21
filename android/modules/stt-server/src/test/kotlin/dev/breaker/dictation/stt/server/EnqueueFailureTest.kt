package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** The enqueue call: every status row, every exchange failure, and every bad enqueue answer. */
class EnqueueFailureTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val BOUNDARY = "0123456789abcdef"
    }

    @Test
    fun `a refused key on enqueue is a key refused failure`() =
        assertEnqueueStatus(401, SttError.OTHER, FailureSentences.KEY_REFUSED)

    @Test
    fun `a 403 on enqueue is a key refused failure`() =
        assertEnqueueStatus(403, SttError.OTHER, FailureSentences.KEY_REFUSED)

    @Test
    fun `a 413 on enqueue is a too large failure`() =
        assertEnqueueStatus(413, SttError.OTHER, FailureSentences.TOO_LARGE)

    @Test
    fun `a 404 on enqueue means the server offers no transcription`() =
        assertEnqueueStatus(404, SttError.SERVER_UNREACHABLE, FailureSentences.NO_ENDPOINT)

    @Test
    fun `a 429 on enqueue means the server is not taking work`() =
        assertEnqueueStatus(429, SttError.SERVER_UNREACHABLE, FailureSentences.NOT_TAKING_WORK)

    @Test
    fun `a 500 on enqueue means the server is not taking work`() =
        assertEnqueueStatus(500, SttError.SERVER_UNREACHABLE, FailureSentences.NOT_TAKING_WORK)

    @Test
    fun `a 599 on enqueue means the server is not taking work`() =
        assertEnqueueStatus(599, SttError.SERVER_UNREACHABLE, FailureSentences.NOT_TAKING_WORK)

    @Test
    fun `a 200 on enqueue is refused because the contract is async only`() {
        val (result, sent) = once(listOf(reply(200, accepted()), reply(200, done())))
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.UNEXPECTED_STATUS), result)
        assertEquals(1, sent.size)
    }

    @Test
    fun `an unlisted status on enqueue is an unexpected status failure`() =
        assertEnqueueStatus(418, SttError.OTHER, FailureSentences.UNEXPECTED_STATUS)

    @Test
    fun `an enqueue answer that is not JSON is a bad answer`() =
        assertAcceptedBody("not json", SttError.OTHER, FailureSentences.BAD_ANSWER)

    @Test
    fun `an enqueue answer without a job id is a bad answer`() =
        assertAcceptedBody("{\"status\":\"queued\"}", SttError.OTHER, FailureSentences.BAD_ANSWER)

    @Test
    fun `an enqueue answer with a numeric job id is a bad answer`() =
        assertAcceptedBody("{\"job_id\":7,\"status\":\"queued\"}", SttError.OTHER, FailureSentences.BAD_ANSWER)

    @Test
    fun `an accepted job that is not queued is a bad answer`() =
        assertAcceptedBody("{\"job_id\":\"job-1\",\"status\":\"processing\"}", SttError.OTHER, FailureSentences.BAD_ANSWER)

    @Test
    fun `a job id with a slash is refused and never polled`() =
        assertAcceptedBody(accepted("job/1"), SttError.OTHER, FailureSentences.BAD_ANSWER)

    @Test
    fun `a job id with dots and a slash is refused and never polled`() =
        assertAcceptedBody(accepted("../x"), SttError.OTHER, FailureSentences.BAD_ANSWER)

    @Test
    fun `a refused connection on enqueue is an unreachable failure`() =
        assertEnqueueException(
            ConnectFailedException(null), SttError.SERVER_UNREACHABLE, FailureSentences.UNREACHABLE,
        )

    @Test
    fun `a failed secure handshake on enqueue is reported at once as a TLS failure`() =
        assertEnqueueException(
            ConnectFailedException(SSLHandshakeException("handshake")),
            SttError.SERVER_UNREACHABLE,
            FailureSentences.TLS_FAILED,
        )

    @Test
    fun `an SSL error on enqueue is a TLS failure`() =
        assertEnqueueException(SSLException("tls"), SttError.SERVER_UNREACHABLE, FailureSentences.TLS_FAILED)

    @Test
    fun `a lost connection on enqueue is a connection lost failure and is never sent twice`() =
        assertEnqueueException(IOException("lost"), SttError.SERVER_UNREACHABLE, FailureSentences.CONNECTION_LOST)

    @Test
    fun `a read timeout on enqueue is a no answer in time failure and is never sent twice`() =
        assertEnqueueException(SocketTimeoutException("slow"), SttError.TIMEOUT, FailureSentences.NO_ANSWER_IN_TIME)

    @Test
    fun `an oversize enqueue reply is a bad answer`() =
        assertEnqueueException(ReplyTooLargeException(), SttError.OTHER, FailureSentences.BAD_ANSWER)

    private fun assertEnqueueStatus(status: Int, error: SttError, sentence: String) {
        val (result, sent) = once(listOf(reply(status, "")))
        assertEquals(SttResult.Failure(error, sentence), result)
        assertEquals(1, sent.size)
    }

    private fun assertEnqueueException(error: Throwable, kind: SttError, sentence: String) {
        val steps = listOf<ScriptedHttp.Step>(
            ScriptedHttp.Step.Throw(error),
            reply(202, accepted()),
            reply(200, done()),
        )
        val (result, sent) = once(steps)
        assertEquals(SttResult.Failure(kind, sentence), result)
        assertEquals(1, sent.size)
    }

    private fun assertAcceptedBody(body: String, error: SttError, sentence: String) {
        val (result, sent) = once(listOf(reply(202, body), reply(200, done())))
        assertEquals(SttResult.Failure(error, sentence), result)
        assertEquals(1, sent.size)
    }

    private fun once(steps: List<ScriptedHttp.Step>): Pair<SttResult, List<ServerRequest>> {
        val ft = FakeTiming()
        val http = ScriptedHttp(steps, ft.clock)
        val result = engineFor(http, ft.timing).transcribe(request())
        return Pair(result, http.requests)
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

    private fun done(): String =
        "{\"status\":\"done\",\"result\":{\"text\":\"hello\",\"language\":\"en\",\"segments\":[]}}"
}
