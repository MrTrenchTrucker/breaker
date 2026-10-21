package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine never throws: transport, provider, interrupt and decoder failures all come back as results. */
class NoThrowTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val BOUNDARY = "0123456789abcdef"
    }

    @Test
    fun `a transport that throws a runtime exception gives an unexpected failure`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(ScriptedHttp.Step.Throw(RuntimeException("boom"))), ft.clock)
        assertEquals(
            SttResult.Failure(SttError.OTHER, FailureSentences.UNEXPECTED),
            engineFor(http, ft.timing).transcribe(request()),
        )
    }

    @Test
    fun `a transport that throws an illegal argument gives an unexpected failure`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(ScriptedHttp.Step.Throw(IllegalArgumentException("bad"))), ft.clock)
        assertEquals(
            SttResult.Failure(SttError.OTHER, FailureSentences.UNEXPECTED),
            engineFor(http, ft.timing).transcribe(request()),
        )
    }

    @Test
    fun `a throwing address provider gives an unexpected failure`() {
        val ft = FakeTiming()
        val engine = SttServerEngine(
            serverUrl = { throw IllegalStateException("address source failed") },
            apiKey = { KEY },
            limits = ServerLimits(),
            http = ScriptedHttp(listOf<ScriptedHttp.Step>(), ft.clock),
            timing = ft.timing,
            boundary = BoundarySource { BOUNDARY },
        )
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.UNEXPECTED), engine.transcribe(request()))
    }

    @Test
    fun `a throwing key provider gives an unexpected failure`() {
        val ft = FakeTiming()
        val engine = SttServerEngine(
            serverUrl = { BASE },
            apiKey = { throw IllegalStateException("key source failed") },
            limits = ServerLimits(),
            http = ScriptedHttp(listOf<ScriptedHttp.Step>(), ft.clock),
            timing = ft.timing,
            boundary = BoundarySource { BOUNDARY },
        )
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.UNEXPECTED), engine.transcribe(request()))
    }

    @Test
    fun `an interrupt during a pause gives the interrupted failure and sets the interrupt flag again`() {
        val ft = FakeTiming()
        val interruptingSleeper = object : PollSleeper {
            override suspend fun sleep(ms: Long) {
                throw InterruptedException("stopped")
            }
        }
        val http = ScriptedHttp(listOf(reply(202, accepted())), ft.clock)
        val engine = engineFor(http, Timing(ft.clock, interruptingSleeper, ft.deadline))
        val result = engine.transcribe(request())
        val flagWasSet = Thread.currentThread().isInterrupted
        Thread.interrupted()
        assertEquals(SttResult.Failure(SttError.OTHER, FailureSentences.INTERRUPTED), result)
        assertTrue("the interrupt flag is set again for the caller", flagWasSet)
    }

    @Test
    fun `a done answer without a result is a bad answer and does not throw`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, "{\"status\":\"done\"}")), ft.clock)
        assertEquals(
            SttResult.Failure(SttError.OTHER, FailureSentences.BAD_ANSWER),
            engineFor(http, ft.timing).transcribe(request()),
        )
    }

    @Test
    fun `a done answer with a segment that ends before it starts is a bad answer and does not throw`() {
        val ft = FakeTiming()
        val body = "{\"status\":\"done\",\"result\":{\"text\":\"x\",\"language\":\"en\"," +
            "\"segments\":[{\"start\":2.0,\"end\":1.0,\"text\":\"x\"}]}}"
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, body)), ft.clock)
        assertEquals(
            SttResult.Failure(SttError.OTHER, FailureSentences.BAD_ANSWER),
            engineFor(http, ft.timing).transcribe(request()),
        )
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

    private fun accepted(): String = "{\"job_id\":\"job-1\",\"status\":\"queued\"}"
}
