package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullLoopHostileTest {

    private fun clientId(n: Int): String =
        "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private fun liveRecord(n: Int): String =
        "{\"client_id\":\"" + clientId(n) + "\",\"change\":" + n +
        ",\"deleted\":false,\"key_version\":0,\"encrypted\":\"x\",\"stored_at\":\"t\"}"

    private fun page(items: List<String>, next: String, more: Boolean): String =
        "{\"items\":[" + items.joinToString(",") + "],\"next\":\"" + next +
        "\",\"more\":" + more + "}"

    @Test
    fun `no progress next equal cursor just sent`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "c1", true))
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: next equal cursor is Refused"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names no progress", refused.reason.startsWith("no progress: "))
        assertEquals("android_sync: two requests then stop", 2, transport.requests.size)
    }

    @Test
    fun `no progress empty page`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(), "c1", true))
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Refused) throw AssertionError("android_sync: empty page is Refused")
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names no progress", refused.reason.startsWith("no progress: "))
        assertEquals("android_sync: one request then stop", 1, transport.requests.size)
    }

    @Test
    fun `page cap at MAX_PAGES`() {
        val transport = ScriptedPullTransport({ requestNum ->
            PullTransportResult.Answered(200, page(
                listOf(liveRecord(requestNum + 1)), "c" + requestNum, true
            ))
        })
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Refused) throw AssertionError("android_sync: page cap is Refused")
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names page cap", refused.reason.startsWith("page cap: "))
        assertEquals("android_sync: exactly MAX_PAGES requests", PullLoop.MAX_PAGES, transport.requests.size)
    }

    @Test
    fun `honest five pages is Ok`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "c2", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(3)), "c3", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(4)), "c4", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(5)), "c5", false))
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Ok) throw AssertionError("android_sync: five pages is Ok")
        val ok = outcome
        assertEquals("android_sync: five records", 5, ok.records.size)
        assertEquals("android_sync: final next", "c5", ok.next)
    }

    @Test
    fun `repeated earlier cursor is not no progress`() {
        val transport = ScriptedPullTransport({ requestNum ->
            val next = if (requestNum % 2 == 0) "c1" else "c0"
            PullTransportResult.Answered(200, page(listOf(liveRecord(requestNum + 1)), next, true))
        })
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: alternating cursor ends at cap"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names page cap", refused.reason.startsWith("page cap: "))
        assertEquals(
            "android_sync: ends at page cap not no progress", PullLoop.MAX_PAGES,
            transport.requests.size
        )
    }
}
