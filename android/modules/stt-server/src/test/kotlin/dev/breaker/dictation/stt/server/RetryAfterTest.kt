package dev.breaker.dictation.stt.server

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retry-After on a 429 is carried on the failure result (no automatic wait). */
class RetryAfterTest {

    private companion object {
        const val BASE = "https://example.test:8443"
        const val KEY = "canary key 7741"
        const val BOUNDARY = "0123456789abcdef"
    }

    @Test
    fun `post 429 with Retry-After 30 carries 30 on the failure`() {
        val (result, _) = once(listOf(reply429("30")))
        val failure = result as SttResult.Failure
        assertEquals(SttError.SERVER_UNREACHABLE, failure.error)
        assertEquals(FailureSentences.NOT_TAKING_WORK, failure.detail)
        assertEquals(30, failure.retryAfterSeconds)
    }

    @Test
    fun `poll 429 with Retry-After 60 carries 60 on the failure`() {
        val (result, _) = once(afterAccept(reply429("60")))
        val failure = result as SttResult.Failure
        assertEquals(SttError.SERVER_UNREACHABLE, failure.error)
        assertEquals(FailureSentences.NOT_TAKING_WORK, failure.detail)
        assertEquals(60, failure.retryAfterSeconds)
    }

    @Test
    fun `post 429 with missing Retry-After header carries null`() {
        val (result, _) = once(listOf(reply(429, "")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with empty Retry-After carries null`() {
        val (result, _) = once(listOf(reply429("")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with garbage text carries null`() {
        val (result, _) = once(listOf(reply429("retry later")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with negative value carries null`() {
        val (result, _) = once(listOf(reply429("-5")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with zero carries null`() {
        val (result, _) = once(listOf(reply429("0")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with 3601 carries null`() {
        val (result, _) = once(listOf(reply429("3601")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with 1 carries 1 since the lower bound is inclusive`() {
        val (result, _) = once(listOf(reply429("1")))
        assertEquals(1, (result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with 3600 carries 3600 since the upper bound is inclusive`() {
        val (result, _) = once(listOf(reply429("3600")))
        assertEquals(3600, (result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with a plus sign carries null`() {
        val (result, _) = once(listOf(reply429("+30")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with Arabic-Indic digits carries null`() {
        val (result, _) = once(listOf(reply429("\u0663\u0660")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with fullwidth digits carries null`() {
        val (result, _) = once(listOf(reply429("\uFF13\uFF10")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with a value that wraps to 1 in 32 bits carries null`() {
        val (result, _) = once(listOf(reply429("4294967297")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with a value above the 32 bit range carries null`() {
        val (result, _) = once(listOf(reply429("99999999999")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with a tab before the digits carries them since trim covers tabs`() {
        val (result, _) = once(listOf(reply429("\t30")))
        assertEquals(30, (result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with a tab after the digits carries them since trim covers tabs`() {
        val (result, _) = once(listOf(reply429("30\t")))
        assertEquals(30, (result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 503 with Retry-After does not carry it and keeps its sentence`() {
        val (result, _) = once(listOf(reply(503, "", mapOf("Retry-After" to listOf("30")))))
        val failure = result as SttResult.Failure
        assertEquals(FailureSentences.NOT_TAKING_WORK, failure.detail)
        assertNull(failure.retryAfterSeconds)
    }

    @Test
    fun `post 404 with Retry-After does not carry it and keeps its sentence`() {
        val (result, _) = once(listOf(reply(404, "", mapOf("Retry-After" to listOf("30")))))
        val failure = result as SttResult.Failure
        assertEquals(FailureSentences.NO_ENDPOINT, failure.detail)
        assertNull(failure.retryAfterSeconds)
    }

    @Test
    fun `post 429 with a huge value carries null and does not throw`() {
        val (result, _) = once(listOf(reply429("99999999999999999999")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with an HTTP-date value carries null`() {
        val (result, _) = once(listOf(reply429("Wed, 21 Oct 2015 07:28:00 GMT")))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with leading and trailing spaces carries the digits`() {
        val (result, _) = once(listOf(reply429("  42  ")))
        assertEquals(42, (result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `post 429 with several Retry-After values carries null`() {
        val (result, _) = once(listOf(reply(429, "", mapOf("Retry-After" to listOf("30", "60")))))
        assertNull((result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `non-429 status with Retry-After does not carry it`() {
        val (result, _) = once(listOf(reply(500, "", mapOf("Retry-After" to listOf("30")))))
        val failure = result as SttResult.Failure
        assertEquals(SttError.SERVER_UNREACHABLE, failure.error)
        assertEquals(FailureSentences.NOT_TAKING_WORK, failure.detail)
        assertNull(failure.retryAfterSeconds)
    }

    @Test
    fun `poll 503 with Retry-After does not carry it`() {
        val (result, _) = once(afterAccept(reply(503, "", mapOf("Retry-After" to listOf("30")))))
        val failure = result as SttResult.Failure
        assertEquals(FailureSentences.NOT_TAKING_WORK, failure.detail)
        assertNull(failure.retryAfterSeconds)
    }

    @Test
    fun `the Retry-After header name is matched without regard to letter case`() {
        val (result, _) = once(listOf(reply(429, "", mapOf("retry-after" to listOf("45")))))
        assertEquals(45, (result as SttResult.Failure).retryAfterSeconds)
    }

    @Test
    fun `the failure sentence is unchanged and holds no digit`() {
        val (result, _) = once(listOf(reply429("30")))
        val failure = result as SttResult.Failure
        assertEquals(FailureSentences.NOT_TAKING_WORK, failure.detail)
        assertFalse("the sentence holds no digit", failure.detail.orEmpty().any { it in '0'..'9' })
    }

    @Test
    fun `nothing is slept for a 429 and the engine returns at once`() {
        val ft = FakeTiming()
        val http = ScriptedHttp(listOf(reply429("30")), ft.clock)
        val result = engineFor(http, ft.timing).transcribe(request())
        assertTrue("a 429 returns at once", result is SttResult.Failure)
        assertTrue("no sleep was requested", ft.sleeper.askedMs.isEmpty())
    }

    private fun reply429(retryAfter: String): ScriptedHttp.Step =
        reply(429, "", mapOf("Retry-After" to listOf(retryAfter)))

    private fun reply(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) =
        ScriptedHttp.Step.Reply(status, body, headers)

    private fun afterAccept(vararg polls: ScriptedHttp.Step): List<ScriptedHttp.Step> =
        listOf<ScriptedHttp.Step>(reply(202, accepted())) + polls.toList()

    private fun once(steps: List<ScriptedHttp.Step>): Pair<SttResult, List<ServerRequest>> {
        val ft = FakeTiming()
        val http = ScriptedHttp(steps, ft.clock)
        val result = engineFor(http, ft.timing).transcribe(request())
        return Pair(result, http.requests)
    }

    private fun request(): SttRequest = SttRequest(FloatArray(16_000), byteArrayOf(1, 2, 3), "small", "en")

    private fun accepted(id: String = "job-1"): String = "{\"job_id\":\"$id\",\"status\":\"queued\"}"

    private fun engineFor(http: ScriptedHttp, timing: Timing): SttServerEngine = SttServerEngine(
        serverUrl = { BASE },
        apiKey = { KEY },
        limits = ServerLimits(maxTransientPollFailures = 0),
        http = http,
        timing = timing,
        boundary = BoundarySource { BOUNDARY },
    )
}
