package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.PullSink
import dev.breaker.shared.api.TranscriptionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for PullCycle: one pull run in order, cursor saved only after apply. */
class PullCycleTest {

    private val clientA = "00000001-0000-4000-8000-000000000001"
    private val clientB = "00000002-0000-4000-8000-000000000002"
    private val storedAt = "2023-11-14T22:13:20.000Z"

    private fun aRecord(
        clientId: String,
        deleted: Boolean = false,
        keyVersion: Int? = 0,
        encrypted: String? = null,
        storedAt: String = this.storedAt,
    ): TranscriptionRecord =
        TranscriptionRecord(
            clientId = clientId,
            change = 1L,
            deleted = deleted,
            keyVersion = keyVersion,
            encrypted = encrypted,
            storedAt = storedAt,
        )

    private fun aBody(text: String = "hello world"):
        dev.breaker.shared.api.sync.SyncBody =
        dev.breaker.shared.api.sync.SyncBody(
            text, dev.breaker.shared.api.sync.SyncSource.LOCAL, "small.en", 1_200, 1_700_000_000_000L,
        )

    private fun encryptedOf(body: dev.breaker.shared.api.sync.SyncBody): String =
        dev.breaker.shared.api.sync.SyncBodyCodec.encodePlain(body)

    private fun pageOf(
        records: List<TranscriptionRecord>,
        next: String,
        more: Boolean,
    ): String {
        val items = records.joinToString(",") { r ->
            "{\"client_id\":\"${r.clientId}\",\"change\":${r.change},\"deleted\":${r.deleted}," +
                "\"key_version\":${r.keyVersion},\"encrypted\":\"${encryptedOf(aBody())}\"," +
                "\"stored_at\":\"${r.storedAt}\"}"
        }
        return "{\"items\":[$items],\"next\":\"$next\",\"more\":$more}"
    }

    private fun runCycle(
        transport: PullTransport,
        cursors: RecordingCursorPort,
        applier: PullApplier,
    ): CycleReport =
        PullCycle(transport, cursors, applier).run()

    // ---- test 1 ----
    /** A stored cursor with an Ok incremental answer applies and saves the next cursor, no follow-up. */
    @Test
    fun `a stored cursor and an incremental answer apply and save the next cursor`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n0", false)),
        ))
        val cursors = RecordingCursorPort("c1")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertEquals("stored cursor read once", 1, cursors.calls.count { it == "pullCursor" })
        assertEquals("next cursor saved", listOf("savePullCursor(n0)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
        assertTrue("cursorSaved true", report.cursorSaved)
        assertFalse("cursorCleared false", report.cursorCleared)
        assertNull("no refusedReason", report.refusedReason)
        assertEquals("first apply stored one", 1, report.first!!.stored)
        assertNull("no second apply", report.second)
        // Accept control: no stored cursor with the same Ok still saves and pulls again.
        val cursors2 = RecordingCursorPort(null)
        val report2 = runCycle(transport, cursors2, applier)
        assertTrue("accept control: no cursor lists from scratch", report2.cursorSaved)
        assertTrue("accept control: full list prunes", report2.first!!.pruned)
    }

    // ---- test 2 ----
    /** No stored cursor lists from scratch, prunes once, saves the list cursor and pulls again from it. */
    @Test
    fun `no stored cursor lists from scratch, prunes once, saves the list cursor and pulls again from it`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n1", false)),
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertEquals("pullCursor read once", 1, cursors.calls.count { it == "pullCursor" })
        assertTrue("full list prunes", report.first!!.pruned)
        assertTrue("list cursor saved after first apply", report.cursorSaved)
        assertTrue("follow-up applied one record", report.second!!.stored == 1)
        assertEquals("two saves: list then follow-up",
            listOf("savePullCursor(n1)", "savePullCursor(n1)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
        assertNull("no refusedReason", report.refusedReason)
        assertFalse("nothing cleared", report.cursorCleared)
        assertEquals("two pull runs (list page, follow-up page)", 2, transport.requests.size)
        assertEquals("follow-up since is n1", "n1", transport.requests[1].since)
        assertEquals("stored ends at n1", "n1", cursors.stored)
    }

    // ---- test 3 ----
    /** The follow-up applies its own records and saves its own next cursor. */
    @Test
    fun `the follow-up applies its own records and saves its own next cursor`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n1", false)),
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientB, encrypted = encryptedOf(aBody()))), "n2", false)),
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertTrue("first apply pruned (full list)", report.first!!.pruned)
        assertTrue("follow-up applied one record", report.second!!.stored == 1)
        assertTrue("cursor saved after first apply", report.cursorSaved)
        assertTrue("cursor saved after follow-up", report.cursorSaved)
        assertNull("no refused reason", report.refusedReason)
        assertEquals("saves in order: n1 then n2",
            listOf("savePullCursor(n1)", "savePullCursor(n2)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
        assertEquals("stored ends at n2", "n2", cursors.stored)
    }

    // ---- test 4 ----
    /** A refused follow-up keeps the list cursor and reports the reason. */
    @Test
    fun `a refused follow-up keeps the list cursor and reports the reason`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n1", false)),
            PullTransportResult.Unreachable,
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertEquals("first apply stored one", 1, report.first!!.stored)
        assertTrue("list cursor saved after first apply", report.cursorSaved)
        assertEquals("refusedReason set", "the server could not be reached", report.refusedReason)
        assertNull("no second apply", report.second)
        assertFalse("nothing cleared on refused follow-up", report.cursorCleared)
        assertEquals("stored cursor kept at n1", "n1", cursors.stored)
        assertEquals("one save only: n1", listOf("savePullCursor(n1)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
    }

    // ---- test 5 ----
    /** No follow-up runs after an incremental pull. */
    @Test
    fun `no follow-up runs after an incremental pull`() {
        val transport = ScriptedPullTransport(
            listOf(PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n0", false)))
        )
        val cursors = RecordingCursorPort("c1")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertFalse("incremental, no prune", report.first!!.pruned)
        assertTrue("next saved", report.cursorSaved)
        assertNull("no second apply after incremental", report.second)
        assertNull("no refused reason", report.refusedReason)
        assertFalse("nothing cleared", report.cursorCleared)
        assertEquals("one pull run only", 1, transport.requests.size)
    }

    // ---- test 6 ----
    /** The report carries what each apply did. */
    @Test
    fun `the report carries what each apply did`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queued.add(clientA)
        val applier = PullApplier(sink, probe)
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n1", false)),
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientB, encrypted = encryptedOf(aBody()))), "n2", false)),
        ))
        val cursors = RecordingCursorPort(null)

        val report = runCycle(transport, cursors, applier)

        assertEquals("first apply skipped queued", 1, report.first!!.skippedQueued)
        assertEquals("first apply stored zero", 0, report.first!!.stored)
        assertEquals("second apply stored one", 1, report.second!!.stored)
        assertEquals("saves: n1 then n2",
            listOf("savePullCursor(n1)", "savePullCursor(n2)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
    }

    // ---- test 7 ----
    /** An unreachable server or another status changes nothing. */
    @Test
    fun `an unreachable server or another status changes nothing`() {
        val transport = ScriptedPullTransport(listOf(PullTransportResult.Unreachable))
        val cursors = RecordingCursorPort("c1")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertNull("no apply on unreachable", report.first)
        assertEquals("refusedReason unreachable", "the server could not be reached", report.refusedReason)
        assertFalse("nothing saved", report.cursorSaved)
        assertFalse("nothing cleared", report.cursorCleared)
        assertEquals("stored cursor untouched", "c1", cursors.stored)

        // Accept control: Other(500) also changes nothing.
        val transport2 = ScriptedPullTransport(listOf(PullTransportResult.Answered(500, "error")))
        val cursors2 = RecordingCursorPort("c1")
        val report2 = runCycle(transport2, cursors2, applier)
        assertNull("no apply on Other", report2.first)
        assertEquals("refusedReason Other", "the server answered status 500", report2.refusedReason)
        assertFalse("nothing saved on Other", report2.cursorSaved)
    }

    // ---- test 8 (C2: follow-up lists again successfully) ----
    /** A follow-up that lists again is applied and pruned and the cycle stops. */
    @Test
    fun `a follow-up that lists again is applied and pruned and the cycle stops`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n1", false)),
            PullTransportResult.Answered(410, "cursor below floor"),
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientB, encrypted = encryptedOf(aBody()))), "n2", false)),
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertTrue("first apply stored one", report.first!!.stored == 1)
        assertTrue("list cursor saved after first apply", report.cursorSaved)
        // The follow-up listed again: run1 Ok fullList=true; run2 hit 410 on n1,
        // listed again once from scratch -> Ok([B], n2) fullList=true.
        assertTrue("second apply stored one (follow-up listed again)", report.second!!.stored == 1)
        assertTrue("second apply pruned (full list)", report.second!!.pruned)
        assertEquals("saves: n1 then n2",
            listOf("savePullCursor(n1)", "savePullCursor(n2)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
        assertEquals("stored ends at n2", "n2", cursors.stored)
        assertNull("no refused reason", report.refusedReason)
        assertEquals("three pull runs: list, follow-up 410, re-list", 3, transport.requests.size)
    }
}
