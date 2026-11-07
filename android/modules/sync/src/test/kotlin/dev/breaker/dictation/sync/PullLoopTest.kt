package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullLoopTest {

    private fun clientId(n: Int): String =
        "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private fun liveRecord(n: Int): String =
        "{\"client_id\":\"" + clientId(n) + "\",\"change\":" + n +
        ",\"deleted\":false,\"key_version\":0,\"encrypted\":\"x\",\"stored_at\":\"t\"}"

    private fun page(items: List<String>, next: String, more: Boolean): String =
        "{\"items\":[" + items.joinToString(",") + "],\"next\":\"" + next +
        "\",\"more\":" + more + "}"

    @Test
    fun `first request uses PullRequest first`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(), "n1", false))
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Ok) throw AssertionError("android_sync: first page is Ok")
        assertEquals("android_sync: first request has no since", null, transport.requests[0].since)
    }

    @Test
    fun `second request sends first page next unchanged`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "c2", false))
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Ok) throw AssertionError("android_sync: two pages is Ok")
        val ok = outcome
        assertEquals("android_sync: records from both pages", 2, ok.records.size)
        assertEquals("android_sync: second request carries first next", "c1", transport.requests[1].since)
    }

    @Test
    fun `records of two pages are in order`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1), liveRecord(2)), "c1", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(3)), "c2", false))
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Ok) throw AssertionError("android_sync: ordered records is Ok")
        val ok = outcome
        assertEquals("android_sync: three records in page order", 3, ok.records.size)
        assertEquals(
            "android_sync: first record is page one record one",
            clientId(1),
            ok.records[0].clientId
        )
        assertEquals(
            "android_sync: last record is page two record one",
            clientId(3),
            ok.records[2].clientId
        )
    }

    @Test
    fun `three pages all read`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "c2", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(3)), "c3", false))
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Ok) throw AssertionError("android_sync: three pages is Ok")
        val ok = outcome
        assertEquals(
            "android_sync: three records total", 3, ok.records.size
        )
        assertEquals(
            "android_sync: final next is page three next", "c3", ok.next
        )
    }

    @Test
    fun `start cursor sent unchanged`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(), "n1", false))
        ))
        val outcome = PullLoop.run(transport, "saved cursor")
        if (outcome !is PullOutcome.Ok) throw AssertionError("android_sync: start cursor is Ok")
        assertEquals(
            "android_sync: first request with start cursor uses it", "saved cursor",
            transport.requests[0].since
        )
    }

    @Test
    fun `empty start cursor uses first`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(), "n1", false))
        ))
        val outcome = PullLoop.run(transport, "")
        if (outcome !is PullOutcome.Ok) throw AssertionError("android_sync: empty start cursor is Ok")
        assertEquals("android_sync: empty start cursor means no since", null, transport.requests[0].since)
    }

    @Test
    fun `BadCursor on first page`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(400, "")
        ))
        val outcome = PullLoop.run(transport)
        assertEquals("android_sync: 400 on first page is BadCursor", PullOutcome.BadCursor, outcome)
    }

    @Test
    fun `BadCursor on later page`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(400, "")
        ))
        val outcome = PullLoop.run(transport)
        assertEquals("android_sync: 400 on later page is BadCursor", PullOutcome.BadCursor, outcome)
        assertEquals("android_sync: second request was made", 2, transport.requests.size)
    }

    @Test
    fun `Other for 401`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(401, "")
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Other) throw AssertionError("android_sync: 401 is Other")
        assertEquals("android_sync: Other holds the status", 401, (outcome as PullOutcome.Other).status)
    }

    @Test
    fun `Other for 410`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(410, "")
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Other) throw AssertionError("android_sync: 410 is Other")
        assertEquals("android_sync: Other holds the status", 410, (outcome as PullOutcome.Other).status)
    }

    @Test
    fun `Other for 500`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(500, "")
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Other) throw AssertionError("android_sync: 500 is Other")
        assertEquals("android_sync: Other holds the status", 500, (outcome as PullOutcome.Other).status)
    }

    @Test
    fun `Unreachable on first page`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Unreachable
        ))
        val outcome = PullLoop.run(transport)
        assertEquals(
            "android_sync: Unreachable on first page is Unreachable", PullOutcome.Unreachable, outcome
        )
    }

    @Test
    fun `Unreachable on later page`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Unreachable
        ))
        val outcome = PullLoop.run(transport)
        assertEquals(
            "android_sync: Unreachable on later page is Unreachable", PullOutcome.Unreachable, outcome
        )
        assertEquals("android_sync: two requests were made", 2, transport.requests.size)
    }

    @Test
    fun `Refused bad body on first page`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, "{not valid json")
        ))
        val outcome = PullLoop.run(transport)
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names the rule", refused.reason.startsWith("page refused: "))
        assertTrue("android_sync: reason holds none of the body text",
            !refused.reason.contains("not valid json"))
    }

    @Test
    fun `Refused bad body on later page`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "c1", true)),
            PullTransportResult.Answered(200, "{not valid json")
        ))
        val outcome = PullLoop.run(transport)
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names the rule", refused.reason.startsWith("page refused: "))
        assertEquals("android_sync: two requests were made", 2, transport.requests.size)
    }

    @Test
    fun `Ok one empty page`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(), "n1", false))
        ))
        val outcome = PullLoop.run(transport)
        if (outcome !is PullOutcome.Ok) throw AssertionError("android_sync: empty page is Ok")
        val ok = outcome
        assertEquals("android_sync: empty records list", 0, ok.records.size)
        assertEquals("android_sync: empty page next", "n1", ok.next)
    }
}
