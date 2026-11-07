package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRunHostileTest {

    private fun clientId(n: Int): String =
        "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private fun liveRecord(n: Int): String =
        "{\"client_id\":\"" + clientId(n) + "\",\"change\":" + n +
        ",\"deleted\":false,\"key_version\":0,\"encrypted\":\"x\",\"stored_at\":\"t\"}"

    private fun page(items: List<String>, next: String, more: Boolean): String =
        "{\"items\":[" + items.joinToString(",") + "],\"next\":\"" + next +
        "\",\"more\":" + more + "}"

    @Test
    fun `all 410 server with stored cursor ends list again refused`() {
        val transport = ScriptedPullTransport({ _ ->
            PullTransportResult.Answered(410, "")
        })
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList false", !result.fullList)
        if (result.outcome !is PullOutcome.Refused) throw AssertionError("android_sync: expected Refused")
        val refused = result.outcome as PullOutcome.Refused
        assertEquals("android_sync: reason is list again refused", PullRun.LIST_AGAIN_REFUSED, refused.reason)
        assertEquals("android_sync: exactly two requests", 2, transport.requests.size)
    }

    @Test
    fun `all 410 server with no cursor ends list refused`() {
        val transport = ScriptedPullTransport({ _ ->
            PullTransportResult.Answered(410, "")
        })
        val result = PullRun.run(transport)
        assertTrue("android_sync: fullList false", !result.fullList)
        if (result.outcome !is PullOutcome.Refused) throw AssertionError("android_sync: expected Refused")
        val refused = result.outcome as PullOutcome.Refused
        assertEquals("android_sync: reason is list refused", PullRun.LIST_REFUSED, refused.reason)
        assertEquals("android_sync: exactly one request", 1, transport.requests.size)
    }

    @Test
    fun `stored cursor refused then endless list ends at page cap`() {
        val transport = ScriptedPullTransport({ requestNum ->
            if (requestNum == 0) PullTransportResult.Answered(400, "")
            else PullTransportResult.Answered(
                200,
                page(listOf(liveRecord(requestNum)), "c" + requestNum, true)
            )
        })
        val result = PullRun.run(transport, "saved cursor")
        assertTrue("android_sync: fullList false", !result.fullList)
        if (result.outcome !is PullOutcome.Refused) throw AssertionError("android_sync: expected Refused")
        val refused = result.outcome as PullOutcome.Refused
        assertTrue("android_sync: reason starts with page cap", refused.reason.startsWith("page cap: "))
        assertEquals(
            "android_sync: one plus MAX_PAGES requests",
            PullLoop.MAX_PAGES + 1,
            transport.requests.size,
        )
    }

    @Test
    fun `transport exception propagates`() {
        val transport = ScriptedPullTransport({ _ ->
            throw IllegalStateException("transport down")
        })
        var threw = false
        try {
            PullRun.run(transport)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("android_sync: exception propagated", threw)
        assertEquals("android_sync: exactly one attempt", 1, transport.requests.size)
    }

    @Test
    fun `list refused reason holds no body text`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(400, "{not valid json")
        ))
        val result = PullRun.run(transport)
        if (result.outcome !is PullOutcome.Refused) throw AssertionError("android_sync: expected Refused")
        val reason = (result.outcome as PullOutcome.Refused).reason
        assertTrue("android_sync: reason holds none of the body", !reason.contains("not valid json"))
    }

    @Test
    fun `list again refused reason holds no body text`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(410, "{hostile body}")
        ))
        val result = PullRun.run(transport, "saved cursor")
        if (result.outcome !is PullOutcome.Refused) throw AssertionError("android_sync: expected Refused")
        val reason = (result.outcome as PullOutcome.Refused).reason
        assertTrue("android_sync: reason holds none of the body", !reason.contains("hostile body"))
    }
}
