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

    // ---- new: incremental cursors must advance strictly past the cursor just sent ----

    @Test
    fun `next not strictly past cursor is refused after one request`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "1-5", true))
        ))
        val outcome = PullLoop.run(transport, "1-10")
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: next behind cursor is Refused"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names no progress", refused.reason.startsWith("no progress: "))
        assertEquals("android_sync: one request then stop", 1, transport.requests.size)
        assertEquals("android_sync: first request since 1-10", "since=1-10", transport.requests[0].query())
    }

    @Test
    fun `second step behind cursor is refused after two requests`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "1-11", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "1-9", true))
        ))
        val outcome = PullLoop.run(transport, "1-10")
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: second step behind cursor is Refused"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names no progress", refused.reason.startsWith("no progress: "))
        assertEquals("android_sync: two requests then stop", 2, transport.requests.size)
    }

    @Test
    fun `next equal cursor still refused`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "1-10", true))
        ))
        val outcome = PullLoop.run(transport, "1-10")
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: next equal cursor is Refused"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names no progress", refused.reason.startsWith("no progress: "))
        assertEquals("android_sync: one request then stop", 1, transport.requests.size)
    }

    @Test
    fun `next strictly past cursor is accepted and the walk goes on`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "1-11", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "1-12", false))
        ))
        val outcome = PullLoop.run(transport, "1-10")
        if (outcome !is PullOutcome.Ok) throw AssertionError(
            "android_sync: next past cursor is Ok"
        )
        assertEquals("android_sync: two requests", 2, transport.requests.size)
        assertEquals("android_sync: second request since 1-11", "since=1-11", transport.requests[1].query())
        assertEquals("android_sync: final next 1-12", "1-12", outcome.next)
    }

    @Test
    fun `account differs is refused`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "2-11", true))
        ))
        val outcome = PullLoop.run(transport, "1-10")
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: next of another account is Refused"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names no progress", refused.reason.startsWith("no progress: "))
        assertEquals("android_sync: one request then stop", 1, transport.requests.size)
    }

    @Test
    fun `a change number past the Int range that advances is accepted`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "1-3000000001", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "1-3000000002", false))
        ))
        val outcome = PullLoop.run(transport, "1-3000000000")
        if (outcome !is PullOutcome.Ok) throw AssertionError(
            "android_sync: a big advancing change number is Ok"
        )
        assertEquals("android_sync: two requests", 2, transport.requests.size)
        assertEquals("android_sync: final next", "1-3000000002", outcome.next)
    }

    @Test
    fun `a change number past the Int range that goes behind is refused after one request`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "1-2999999999", true))
        ))
        val outcome = PullLoop.run(transport, "1-3000000000")
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: a big change number behind the cursor is Refused"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason names no progress", refused.reason.startsWith("no progress: "))
        assertTrue("android_sync: it is the behind rule, not the equal rule", refused.reason.contains("not past"))
        assertEquals("android_sync: one request then stop", 1, transport.requests.size)
    }

    @Test
    fun `a cursor with a letter in the account part is outside the rule`() {
        // a-10 is not two digit parts, so the rule is off: the behind next is accepted and only the
        // equality check ends the pull on the second request (never the not-past or account refusal).
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "a-5", true))
        ))
        val outcome = PullLoop.run(transport, "a-10")
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: a letter account cursor ends by the equality check"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason is the equality refusal", refused.reason.contains("equals the cursor"))
        assertEquals("android_sync: two requests, the rule did not fire on the first", 2, transport.requests.size)
    }

    @Test
    fun `a next that is not two digit parts is outside the rule`() {
        // 1-1 is incremental but x-2 is not: the rule is off, so x-2 is not refused as another account.
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "x-2", true))
        ))
        val outcome = PullLoop.run(transport, "1-1")
        if (outcome !is PullOutcome.Refused) throw AssertionError(
            "android_sync: an unparseable next ends by the equality check"
        )
        val refused = outcome as PullOutcome.Refused
        assertTrue("android_sync: reason is the equality refusal", refused.reason.contains("equals the cursor"))
        assertEquals("android_sync: two requests, the rule did not fire on the first", 2, transport.requests.size)
    }

    @Test
    fun `listing cursor is not subject to the incremental rule`() {
        // A listing cursor (3 parts) keeps the old equality-only behaviour: even an incremental
        // next that is not past the listing position is accepted, because the rule is
        // incremental-only and never touches listing cursors. Page 1 has more=true so the
        // refusal branch IS reached; page 2 ends the pull.
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(liveRecord(1)), "1-3", true)),
            PullTransportResult.Answered(200, page(listOf(liveRecord(2)), "1-4", false))
        ))
        val outcome = PullLoop.run(transport, "1-5-9")
        if (outcome !is PullOutcome.Ok) throw AssertionError(
            "android_sync: listing cursor keeps equality-only behaviour"
        )
        assertEquals("android_sync: two records", 2, outcome.records.size)
        assertEquals("android_sync: two requests", 2, transport.requests.size)
        assertEquals("android_sync: final next 1-4", "1-4", outcome.next)
    }
}
