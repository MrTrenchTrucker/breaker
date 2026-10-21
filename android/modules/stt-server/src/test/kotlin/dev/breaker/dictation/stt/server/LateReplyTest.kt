package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Deadline double whose gate can be renewed between calls. Only the wait that starts while the
 * current gate is open returns at once, so a later call on the same engine waits on its own gate.
 */
private class LateReplyTestDeadline : DeadlineTimer {
    private val gate = AtomicReference(CompletableDeferred<Unit>())

    fun renew() {
        gate.set(CompletableDeferred())
    }

    fun openNow() {
        gate.get().complete(Unit)
    }

    override suspend fun await(ms: Long) {
        gate.get().await()
    }
}

/**
 * Transport wrapper: when armed it opens the deadline just before the next exchange, and it refuses an
 * exchange whose abort handle has already fired, as the real transport does.
 */
private class LateReplyTestTransport(
    private val inner: ServerHttp,
    private val deadline: LateReplyTestDeadline,
) : ServerHttp {
    private val openNext = AtomicBoolean(false)

    fun openOnNextExchange() {
        openNext.set(true)
    }

    override fun exchange(request: ServerRequest, abort: AbortHandle): ServerReply {
        if (openNext.getAndSet(false)) deadline.openNow()
        if (abort.isAborted) throw IOException("the exchange was already aborted")
        return inner.exchange(request, abort)
    }
}

/** A reply that arrives after the deadline is never used, and a later call on the same engine gets its own answer. */
class LateReplyTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val BOUNDARY = "0123456789abcdef"
    }

    @Test
    fun `a late reply after the deadline is never used and the call stays a timeout`() {
        val ft = FakeTiming()
        val deadline = LateReplyTestDeadline()
        val scripted = ScriptedHttp(
            listOf(
                ScriptedHttp.Step.Stall(ServerReply(202, accepted("late-job").toByteArray(Charsets.UTF_8))),
                ScriptedHttp.Step.Reply(200, done("unused")),
            ),
            ft.clock,
        )
        val transport = LateReplyTestTransport(scripted, deadline)
        transport.openOnNextExchange()
        val engine = engineFor(transport, Timing(ft.clock, ft.sleeper, deadline))
        assertEquals(
            SttResult.Failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED),
            engine.transcribe(request()),
        )
        assertEquals(1, scripted.requests.size)
    }

    @Test
    fun `a second call on the same engine gets its own answer and is not refused by the first abort`() {
        val ft = FakeTiming()
        val deadline = LateReplyTestDeadline()
        val scripted = ScriptedHttp(
            listOf(
                ScriptedHttp.Step.Stall(ServerReply(202, accepted("late-job").toByteArray(Charsets.UTF_8))),
                ScriptedHttp.Step.Reply(202, accepted("job-2")),
                ScriptedHttp.Step.Reply(200, done("second")),
            ),
            ft.clock,
        )
        val transport = LateReplyTestTransport(scripted, deadline)
        val engine = engineFor(transport, Timing(ft.clock, ft.sleeper, deadline))
        transport.openOnNextExchange()
        engine.transcribe(request())
        deadline.renew()
        val second = engine.transcribe(request())
        assertTrue("the second call returns its own scripted text", second is SttResult.Success && second.text == "second")
        assertEquals("$BASE/v1/jobs/job-2", scripted.requests[2].url)
    }

    private fun request(): SttRequest = SttRequest(FloatArray(16_000), byteArrayOf(1, 2, 3), "small", "en")

    private fun engineFor(http: ServerHttp, timing: Timing): SttServerEngine = SttServerEngine(
        serverUrl = { BASE },
        apiKey = { KEY },
        limits = ServerLimits(),
        http = http,
        timing = timing,
        boundary = BoundarySource { BOUNDARY },
    )

    private fun accepted(id: String): String = "{\"job_id\":\"$id\",\"status\":\"queued\"}"

    private fun done(text: String): String =
        "{\"status\":\"done\",\"result\":{\"text\":\"$text\",\"language\":\"en\",\"segments\":[]}}"
}
