package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRunTest {

    private fun clientId(n: Int): String =
        "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private fun liveRecord(n: Int): String =
        "{\"client_id\":\"" + clientId(n) + "\",\"change\":" + n +
        ",\"deleted\":false,\"key_version\":0,\"encrypted\":\"x\",\"stored_at\":\"t\"}"

    private fun page(items: List<String>, next: String, more: Boolean): String =
        "{\"items\":[" + items.joinToString(",") + "],\"next\":\"" + next +
        "\",\"more\":" + more + "}"

    @Test
    fun `no cursor and Ok is full list`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "n1", false))
        ))
        val result = PullRun.run(transport)
        assertTrue("android_sync: fullList true", result.fullList)
        if (result.outcome !is PullOutcome.Ok) throw AssertionError("android_sync: expected Ok")
        assertEquals("android_sync: one request", 1, transport.requests.size)
    }

    @Test
    fun `no cursor and 400 is list refused`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(400, "")
        ))
        val result = PullRun.run(transport)
        assertTrue("android_sync: fullList false", !result.fullList)
        val refused = result.outcome as PullOutcome.Refused
        assertEquals("android_sync: reason is list refused", PullRun.LIST_REFUSED, refused.reason)
        assertEquals("android_sync: exactly one request", 1, transport.requests.size)
    }

    @Test
    fun `no cursor and 410 is list refused`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(410, "")
        ))
        val result = PullRun.run(transport)
        assertTrue("android_sync: fullList false", !result.fullList)
        val refused = result.outcome as PullOutcome.Refused
        assertEquals("android_sync: reason is list refused", PullRun.LIST_REFUSED, refused.reason)
        assertEquals("android_sync: exactly one request", 1, transport.requests.size)
    }

    @Test
    fun `stored cursor and Ok is not full list`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "n1", false))
        ))
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList false", !result.fullList)
        if (result.outcome !is PullOutcome.Ok) throw AssertionError("android_sync: expected Ok")
        val since0 = transport.requests[0].since
        assertEquals("android_sync: saved cursor is first request since", since0, "saved cursor")
        assertEquals("android_sync: exactly one request", 1, transport.requests.size)
    }

    @Test
    fun `stored cursor refused with 400 then list ok`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(400, ""),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "n1", false))
        ))
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList true", result.fullList)
        if (result.outcome !is PullOutcome.Ok) throw AssertionError("android_sync: expected Ok")
        val ok = result.outcome as PullOutcome.Ok
        assertEquals("android_sync: only second attempt records", 1, ok.records.size)
        assertEquals("android_sync: second request carries first next", "c1", transport.requests[1].since)
        assertEquals("android_sync: third request has no since", null, transport.requests[2].since)
        assertEquals("android_sync: exactly three requests", 3, transport.requests.size)
    }

    @Test
    fun `stored cursor refused with 410 then list ok`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(410, ""),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "n1", false))
        ))
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList true", result.fullList)
        if (result.outcome !is PullOutcome.Ok) throw AssertionError("android_sync: expected Ok")
        val ok = result.outcome as PullOutcome.Ok
        assertEquals("android_sync: only second attempt records", 1, ok.records.size)
        assertEquals("android_sync: second request carries first next", "c1", transport.requests[1].since)
        assertEquals("android_sync: third request has no since", null, transport.requests[2].since)
        assertEquals("android_sync: exactly three requests", 3, transport.requests.size)
    }

    @Test
    fun `first attempt records dropped on re list`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(410, ""),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "n1", false))
        ))
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList true", result.fullList)
        if (result.outcome !is PullOutcome.Ok) throw AssertionError("android_sync: expected Ok")
        val ok = result.outcome as PullOutcome.Ok
        assertEquals("android_sync: only second attempt records", 1, ok.records.size)
        assertEquals("android_sync: second attempt record id", clientId(2), ok.records[0].clientId)
        val firstMissing = !ok.records.any { it.clientId == clientId(1) }
        assertTrue("android_sync: first attempt record id not present", firstMissing)
    }

    @Test
    fun `refusal on later page then good list`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(410, ""),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "n1", false))
        ))
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList true", result.fullList)
        if (result.outcome !is PullOutcome.Ok) throw AssertionError("android_sync: expected Ok")
        val ok = result.outcome as PullOutcome.Ok
        assertEquals("android_sync: one record", 1, ok.records.size)
        assertEquals("android_sync: second attempt record id", clientId(2), ok.records[0].clientId)
        assertEquals("android_sync: exactly three requests", 3, transport.requests.size)
    }

    @Test
    fun `stored cursor and 500 is unchanged other`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(500, "")
        ))
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList false", !result.fullList)
        if (result.outcome !is PullOutcome.Other) throw AssertionError("android_sync: expected Other")
        assertEquals("android_sync: Other holds status", 500, (result.outcome as PullOutcome.Other).status)
        assertEquals("android_sync: exactly one request", 1, transport.requests.size)
    }

    @Test
    fun `stored cursor and unreachable is unchanged`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Unreachable
        ))
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList false", !result.fullList)
        if (result.outcome !is PullOutcome.Unreachable) throw AssertionError(
            "android_sync: expected Unreachable"
        )
        assertEquals("android_sync: exactly one request", 1, transport.requests.size)
    }

    @Test
    fun `empty stored cursor behaves as none`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "n1", false))
        ))
        val result = PullRun.run(transport, "")
        assertTrue("android_sync: fullList true", result.fullList)
        if (result.outcome !is PullOutcome.Ok) throw AssertionError("android_sync: expected Ok")
        assertEquals("android_sync: exactly one request", 1, transport.requests.size)
    }

    @Test
    fun `from scratch later page 400 is list refused`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(400, "")
        ))
        val result = PullRun.run(transport)
        assertTrue("android_sync: fullList false", !result.fullList)
        val refused = result.outcome as PullOutcome.Refused
        assertEquals("android_sync: reason is list refused", PullRun.LIST_REFUSED, refused.reason)
        assertEquals("android_sync: exactly two requests", 2, transport.requests.size)
        assertEquals("android_sync: second request carries first next", "c1", transport.requests[1].since)
    }

    @Test
    fun `from scratch later page ok is full list`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "n1", false))
        ))
        val result = PullRun.run(transport)
        assertTrue("android_sync: fullList true", result.fullList)
        if (result.outcome !is PullOutcome.Ok) throw AssertionError("android_sync: expected Ok")
        val ok = result.outcome as PullOutcome.Ok
        assertEquals("android_sync: two records", 2, ok.records.size)
    }

    @Test
    fun `no cursor endless list ends at page cap`() {
        val transport = ScriptedPullTransport({ requestNum ->
            PullTransportResult.Answered(
                200,
                page(listOf(liveRecord(requestNum + 1)), "c" + requestNum, true)
            )
        })
        val result = PullRun.run(transport)
        assertTrue("android_sync: fullList false", !result.fullList)
        val refused = result.outcome as PullOutcome.Refused
        assertTrue("android_sync: reason starts with page cap", refused.reason.startsWith("page cap: "))
        assertEquals("android_sync: exactly MAX_PAGES requests", PullLoop.MAX_PAGES, transport.requests.size)
    }
}
