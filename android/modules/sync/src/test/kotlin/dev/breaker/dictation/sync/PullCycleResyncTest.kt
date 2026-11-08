package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.PullSink
import dev.breaker.shared.api.TranscriptionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resync tests for PullCycle: 410, second refusal, refused first, throwing apply/port. */
class PullCycleResyncTest {

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
            val enc = if (r.encrypted == null) "null" else "\"${r.encrypted}\""
            val kv = if (r.keyVersion == null) "null" else r.keyVersion.toString()
            "{\"client_id\":\"${r.clientId}\",\"change\":${r.change},\"deleted\":${r.deleted}," +
                "\"key_version\":$kv,\"encrypted\":$enc,\"stored_at\":\"${r.storedAt}\"}"
        }
        return "{\"items\":[$items],\"next\":\"$next\",\"more\":$more}"
    }

    private fun answer200(vararg records: TranscriptionRecord): PullTransportResult =
        PullTransportResult.Answered(200, pageOf(records.toList(), "n1", false))

    private fun answer200Next(vararg records: TranscriptionRecord): PullTransportResult =
        PullTransportResult.Answered(200, pageOf(records.toList(), "n2", false))

    private fun answer400(): PullTransportResult =
        PullTransportResult.Answered(400, "bad cursor")

    private fun answer410(): PullTransportResult =
        PullTransportResult.Answered(410, "cursor below floor")

    private fun runCycle(
        transport: PullTransport,
        cursors: RecordingCursorPort,
        applier: PullApplier,
    ): CycleReport =
        PullCycle(transport, cursors, applier).run()

    private fun thrownBy(block: () -> Unit): Throwable? =
        try {
            block(); null
        } catch (failure: Throwable) {
            failure
        }

    // ---- test 1 ----
    /** A 410 on the stored cursor lists again, prunes, saves and pulls again from the new cursor. */
    @Test
    fun `a 410 on the stored cursor lists again, prunes, saves and pulls again from the new cursor`() {
        val transport = ScriptedPullTransport(listOf(
            answer410(),
            answer200(aRecord(clientA, encrypted = encryptedOf(aBody()))),
            answer200Next(aRecord(clientB, encrypted = encryptedOf(aBody()))),
        ))
        val cursors = RecordingCursorPort("c1")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertTrue("first apply pruned", report.first!!.pruned)
        assertTrue("list cursor saved", report.cursorSaved)
        assertTrue("follow-up applied one record", report.second!!.stored == 1)
        assertFalse("nothing cleared", report.cursorCleared)
        assertNull("no refused reason", report.refusedReason)
        assertEquals("list cursor was read", 1, cursors.calls.count { it == "pullCursor" })
        assertEquals("two saves (list then follow-up)",
            listOf("savePullCursor(n1)", "savePullCursor(n2)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
        assertEquals("three pull runs (410, from scratch, from next)", 3, transport.requests.size)
    }

    // ---- test 2 ----
    /** A second refusal clears the stored cursor and saves and applies nothing. */
    @Test
    fun `a second refusal clears the stored cursor and saves and applies nothing`() {
        val transport = ScriptedPullTransport(listOf(answer400(), answer410()))
        val cursors = RecordingCursorPort("c1")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertNull("no first apply", report.first)
        assertNull("no second apply", report.second)
        assertEquals("refused reason is LIST_AGAIN_REFUSED",
            "listed again once and refused again", report.refusedReason)
        assertFalse("nothing saved", report.cursorSaved)
        assertTrue("cursor cleared", report.cursorCleared)
        assertEquals("stored cursor emptied", null, cursors.stored)
        assertEquals("one clear call", 1, cursors.clearCalls)
        assertEquals("no save call on the port", 0, cursors.saveCalls)
        assertEquals("sink untouched", 0, sink.rows.size)
        assertEquals("two pull runs", 2, transport.requests.size)
    }

    // ---- test 3 ----
    /** No stored cursor and a refused first list clears nothing and saves nothing. */
    @Test
    fun `no stored cursor and a refused first list clears nothing and saves nothing`() {
        val transport = ScriptedPullTransport(listOf(answer400()))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertNull("no first apply", report.first)
        assertNull("no second apply", report.second)
        assertEquals("refused reason is LIST_REFUSED",
            "the list was refused", report.refusedReason)
        assertFalse("nothing saved", report.cursorSaved)
        assertFalse("nothing cleared (stored was null)", report.cursorCleared)
        assertEquals("stored cursor still null", null, cursors.stored)
        assertEquals("no clear call", 0, cursors.clearCalls)
        assertEquals("sink untouched", 0, sink.rows.size)
        assertEquals("one pull run", 1, transport.requests.size)
    }

    // ---- test 4 ----
    /** An apply that throws saves no cursor. */
    @Test
    fun `an apply that throws saves no cursor`() {
        val transport = ScriptedPullTransport(
            listOf(PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n1", false)))
        )
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        sink.throwOn = RuntimeException("sink down")
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val failure = thrownBy { runCycle(transport, cursors, applier) }

        assertNotNull("apply throw propagates", failure)
        assertTrue("throw is a RuntimeException", failure is RuntimeException)
        assertNull("cursor not saved after throwing apply", cursors.stored)
        assertEquals("no save call", 0, cursors.saveCalls)
    }

    // ---- test 5 ----
    /** A throwing cursor port after a good apply propagates and the next cycle repeats safely. */
    @Test
    fun `a throwing cursor port after a good apply propagates and the next cycle repeats safely`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n1", false)),
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n2", false)),
        ))
        val cursors = RecordingCursorPort(null)
        cursors.throwOnSave = RuntimeException("save down")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val failure1 = thrownBy { runCycle(transport, cursors, applier) }
        assertNotNull("cycle 1 save throw propagates", failure1)
        assertEquals("apply ran once in cycle 1", 1, sink.rows.size)
        assertNull("stored still null after cycle 1", cursors.stored)

        val failure2 = thrownBy { runCycle(transport, cursors, applier) }
        assertNotNull("cycle 2 save throw propagates", failure2)
        assertEquals("apply ran again in cycle 2", 2, sink.rows.size)
    }

    // ---- test 6 ----
    /** A follow-up that is itself refused lists again and the cycle makes no third run. */
    @Test
    fun `a follow-up that is itself refused lists again and the cycle makes no third run`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200,
                pageOf(listOf(aRecord(clientA, encrypted = encryptedOf(aBody()))), "n1", false)),
            answer400(),
            answer410(),
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertTrue("first apply stored one", report.first!!.stored == 1)
        assertTrue("list cursor saved", report.cursorSaved)
        assertNull("no second apply (follow-up refused)", report.second)
        assertEquals("refused reason from follow-up",
            "listed again once and refused again", report.refusedReason)
        assertFalse("nothing cleared", report.cursorCleared)
        assertEquals("stored cursor kept (saved after first apply)", "n1", cursors.stored)
        assertEquals("no third pull run", 3, transport.requests.size)
    }
}
