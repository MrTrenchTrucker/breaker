package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
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

/** Transport wrapper: refuses an exchange whose abort handle has already fired. The test helper opens
 * the deadline only after the stalled exchange has recorded its request and entered the stall, so the
 * watchdog can never abort before the request is counted.
 */
private class LateReplyTestTransport(
    private val inner: ServerHttp,
    private val deadline: LateReplyTestDeadline,
) : ServerHttp {
    override fun exchange(request: ServerRequest, abort: AbortHandle): ServerReply {
        if (abort.isAborted) throw IOException("the exchange was already aborted")
        return inner.exchange(request, abort)
    }
}

/** A reply after the deadline is never used; a later call gets its own answer. */
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
        val late = ServerReply(202, accepted("late-job").toByteArray(Charsets.UTF_8))
        val stall = ScriptedHttp.Step.Stall(late)
        val scripted = ScriptedHttp(
            listOf(
                stall,
                ScriptedHttp.Step.Reply(200, done("unused")),
            ),
            ft.clock,
        )
        val transport = LateReplyTestTransport(scripted, deadline)
        val engine = engineFor(transport, Timing(ft.clock, ft.sleeper, deadline))
        val opened = CompletableDeferred<Unit>()
        val recorded = AtomicInteger(-1)
        val helper = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        helper.launch {
            stall.entered.await()
            recorded.set(scripted.requests.size)
            deadline.openNow()
            opened.complete(Unit)
        }
        try {
            assertEquals(
                SttResult.Failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED),
                engine.transcribe(request()),
            )
            runBlocking { opened.await() }
            assertEquals(
                "android_stt_server: the deadline opened after the request was recorded",
                1,
                recorded.get(),
            )
            assertEquals(1, scripted.requests.size)
        } finally {
            helper.cancel()
        }
    }

    @Test
    fun `a second call on the same engine gets its own answer and is not refused by the first abort`() {
        val ft = FakeTiming()
        val deadline = LateReplyTestDeadline()
        val late = ServerReply(202, accepted("late-job").toByteArray(Charsets.UTF_8))
        val stall = ScriptedHttp.Step.Stall(late)
        val scripted = ScriptedHttp(
            listOf(
                stall,
                ScriptedHttp.Step.Reply(202, accepted("job-2")),
                ScriptedHttp.Step.Reply(200, done("second")),
            ),
            ft.clock,
        )
        val transport = LateReplyTestTransport(scripted, deadline)
        val engine = engineFor(transport, Timing(ft.clock, ft.sleeper, deadline))
        val opened = CompletableDeferred<Unit>()
        val recorded = AtomicInteger(-1)
        val helper = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        helper.launch {
            stall.entered.await()
            recorded.set(scripted.requests.size)
            deadline.openNow()
            opened.complete(Unit)
        }
        try {
            assertEquals(
                SttResult.Failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED),
                engine.transcribe(request()),
            )
            runBlocking { opened.await() }
            assertEquals(
                "android_stt_server: the deadline opened after the request was recorded",
                1,
                recorded.get(),
            )
            assertEquals(1, scripted.requests.size)
        } finally {
            helper.cancel()
        }
        deadline.renew()
        val second = engine.transcribe(request())
        val ok = second is SttResult.Success && second.text == "second"
        assertTrue("the second call returns its own scripted text", ok)
        assertEquals("$BASE/v1/jobs/job-2", scripted.requests[2].url)
    }

    private fun request(): SttRequest =
        SttRequest(FloatArray(16_000), byteArrayOf(1, 2, 3), "small", "en")

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
