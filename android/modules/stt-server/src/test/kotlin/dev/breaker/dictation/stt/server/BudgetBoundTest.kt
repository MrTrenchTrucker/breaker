package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Deadline double: records each requested wait and returns once [gate] is completed. */
private class BudgetBoundTestDeadline : DeadlineTimer {
    val gate: CompletableDeferred<Unit> = CompletableDeferred()
    private val asked = AtomicReference<List<Long>>(emptyList())

    val askedMs: List<Long>
        get() = asked.get()

    override suspend fun await(ms: Long) {
        asked.updateAndGet { it + ms }
        gate.await()
    }
}

/** Passes each exchange to [inner], keeps the abort handle it was given, and runs [onGet] before each GET. */
private class BudgetBoundTestProbe(
    private val inner: ServerHttp,
    private val onGet: () -> Unit,
) : ServerHttp {
    private val last = AtomicReference<AbortHandle?>(null)

    val lastAbort: AbortHandle?
        get() = last.get()

    override fun exchange(request: ServerRequest, abort: AbortHandle): ServerReply {
        last.set(abort)
        if (request.method == "GET") onGet()
        return inner.exchange(request, abort)
    }
}

/** The whole-call budget, the per-call timeouts and pauses, the poll count cap and the watchdog expiry. */
class BudgetBoundTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val BOUNDARY = "0123456789abcdef"
        val BUDGET_USED = SttResult.Failure(SttError.TIMEOUT, FailureSentences.BUDGET_USED)
    }

    @Test
    fun `a slow enqueue call that uses the whole budget ends before any poll`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(
            listOf(ScriptedHttp.Step.ReplyAfter(63_000L, 202, accepted()), reply(200, done())),
            ft.clock,
        )
        assertEquals(BUDGET_USED, engineFor(http, ft.timing).transcribe(request()))
        assertEquals(1, http.requests.size)
        assertTrue("no pause was asked for once the budget is spent", ft.sleeper.askedMs.isEmpty())
    }

    @Test
    fun `the poll timeouts and the pause are cut to the time left after a slow enqueue`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(
            listOf(ScriptedHttp.Step.ReplyAfter(62_000L, 202, accepted()), queued(), reply(200, done())),
            ft.clock,
        )
        assertEquals(BUDGET_USED, engineFor(http, ft.timing).transcribe(request()))
        assertEquals(2, http.requests.size)
        assertEquals(1500, http.requests[0].connectTimeoutMs)
        assertEquals(10_000, http.requests[0].readTimeoutMs)
        assertEquals(500, http.requests[1].connectTimeoutMs)
        assertEquals(500, http.requests[1].readTimeoutMs)
        assertEquals(listOf(500L, 500L), ft.sleeper.askedMs)
    }

    @Test
    fun `the poll loop stops after its poll count cap and never polls again`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(
            listOf(reply(202, accepted()), queued(), queued(), queued(), reply(200, done())),
            ft.clock,
        )
        val result = engineFor(http, ft.timing, ServerLimits(maxPolls = 3)).transcribe(request())
        assertEquals(BUDGET_USED, result)
        assertEquals(4, http.requests.size)
        assertEquals(listOf(500L, 750L, 1125L), ft.sleeper.askedMs)
    }

    @Test
    fun `the pause between polls grows and stops at the delay cap`() {
        val ft = FakeTiming()
        val steps = listOf<ScriptedHttp.Step>(
            reply(202, accepted()),
            queued(), queued(), queued(), queued(), queued(),
            reply(200, done()),
        )
        val result = engineFor(ScriptedHttp(steps, ft.clock), ft.timing).transcribe(request())
        assertTrue("the done poll after five queued polls is a success", result is SttResult.Success)
        assertEquals(listOf(500L, 750L, 1125L, 1687L, 2000L, 2000L), ft.sleeper.askedMs)
    }

    @Test
    fun `a one second clip gets three seconds of budget on top of the base budget`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(
            listOf(ScriptedHttp.Step.ReplyAfter(61_000L, 202, accepted()), reply(200, done())),
            ft.clock,
        )
        val result = engineFor(http, ft.timing).transcribe(request())
        assertTrue("the poll still has time after a slow enqueue", result is SttResult.Success)
    }

    @Test
    fun `a clip longer than the cap gets the maximum budget and no more`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(
            listOf(ScriptedHttp.Step.ReplyAfter(300_000L, 202, accepted()), reply(200, done())),
            ft.clock,
        )
        assertEquals(BUDGET_USED, engineFor(http, ft.timing).transcribe(request(1_300_000)))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a stalled enqueue call ends at its deadline with the budget sentence and aborts its exchange`() {
        val ft = FakeTiming()
        ft.deadline.gate.complete(Unit)
        val probe = BudgetBoundTestProbe(ScriptedHttp(listOf(ScriptedHttp.Step.Stall())), onGet = {})
        assertEquals(BUDGET_USED, engineFor(probe, ft.timing).transcribe(request()))
        assertTrue("the stalled exchange was aborted at the deadline", probe.lastAbort?.isAborted == true)
    }

    @Test
    fun `a stalled poll ends at the time left in the budget and not at the poll cap`() {
        val ft = FakeTiming()
        val deadline = BudgetBoundTestDeadline()
        val scripted = ScriptedHttp(
            listOf(ScriptedHttp.Step.ReplyAfter(60_000L, 202, accepted()), ScriptedHttp.Step.Stall()),
            ft.clock,
        )
        val probe = BudgetBoundTestProbe(scripted, onGet = { deadline.gate.complete(Unit) })
        val engine = engineFor(probe, Timing(ft.clock, ft.sleeper, deadline), ServerLimits(maxTransientPollFailures = 0))
        assertEquals(BUDGET_USED, engine.transcribe(request()))
        assertEquals(2, scripted.requests.size)
        assertEquals(2500, scripted.requests[1].readTimeoutMs)
        assertTrue("the poll watchdog was given the time left", deadline.askedMs.contains(2500L))
        assertFalse("the poll watchdog was not given the poll cap", deadline.askedMs.contains(10_000L))
        assertTrue("the stalled poll was aborted", probe.lastAbort?.isAborted == true)
    }

    @Test
    fun `a clip past the cap still gets a poll after an enqueue that takes 299 seconds`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(
            listOf(ScriptedHttp.Step.ReplyAfter(299_000L, 202, accepted()), reply(200, done())),
            ft.clock,
        )
        val result = engineFor(http, ft.timing).transcribe(request(1_300_000))
        assertTrue("the done poll is a success under the maximum budget", result is SttResult.Success)
        assertEquals(listOf(500L), ft.sleeper.askedMs)
    }

    @Test
    fun `the post request asks for a connect and read timeout no longer than a one second budget`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, done())), ft.clock)
        engineFor(http, ft.timing, oneSecondBudget()).transcribe(request())
        assertEquals(1000, http.requests[0].connectTimeoutMs)
        assertEquals(1000, http.requests[0].readTimeoutMs)
    }

    @Test
    fun `the post request keeps the connect limit and cuts the read timeout to a four second budget`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply(202, accepted()), reply(200, done())), ft.clock)
        engineFor(http, ft.timing, fourSecondBudget()).transcribe(request())
        assertEquals(1500, http.requests[0].connectTimeoutMs)
        assertEquals(4000, http.requests[0].readTimeoutMs)
    }

    @Test
    fun `the post watchdog waits for the four second budget and not for the post call cap`() {
        val ft = FakeTiming()
        val deadline = BudgetBoundTestDeadline()
        deadline.gate.complete(Unit)
        val probe = BudgetBoundTestProbe(ScriptedHttp(listOf(ScriptedHttp.Step.Stall())), onGet = {})
        val engine = engineFor(probe, Timing(ft.clock, ft.sleeper, deadline), fourSecondBudget())
        assertEquals(BUDGET_USED, engine.transcribe(request()))
        assertEquals(listOf(4_000L), deadline.askedMs)
    }

    private fun oneSecondBudget(): ServerLimits =
        ServerLimits(baseBudgetMs = 1_000L, clipFactor = 0, maxBudgetMs = 1_000L)

    private fun fourSecondBudget(): ServerLimits =
        ServerLimits(baseBudgetMs = 4_000L, clipFactor = 0, maxBudgetMs = 4_000L)

    private fun request(clipSamples: Int = 16_000): SttRequest =
        SttRequest(FloatArray(clipSamples), byteArrayOf(1, 2, 3), "small", "en")

    private fun reply(status: Int, body: String) = ScriptedHttp.Step.Reply(status, body)

    private fun queued() = reply(200, "{\"status\":\"queued\"}")

    private fun engineFor(
        http: ServerHttp,
        timing: Timing,
        limits: ServerLimits = ServerLimits(),
    ): SttServerEngine = SttServerEngine(
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
}
