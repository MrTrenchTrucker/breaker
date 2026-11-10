package dev.breaker.dictation.sync

import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole-chain test of PullCycle over a scripted PullTransport with the real PullApplier,
 * recording cursor and recording sink. No main code is changed.
 *
 * Pages are built with the page() helper (PullLoopTest.kt:16 pattern) and live rows carry
 * a real encrypted body from SyncBodyCodec.encodePlain (PullCycleTest.kt:42-45 pattern).
 * The fixture delete row stored_at "2023-11-14T22:13:20.123Z" parses to 1700000000123 ms
 * (SyncBodyTime.kt:62-78).
 */
class PullChainTest {

    private val clientA = "00000001-0000-4000-8000-000000000001"
    private val clientB = "00000002-0000-4000-8000-000000000002"
    private val clientC = "00000003-0000-4000-8000-000000000003"

    private fun body(text: String, createdAtMs: Long): SyncBody =
        SyncBody(text, SyncSource.SERVER, "small.en", 1_200, createdAtMs)

    private fun encryptedOf(body: SyncBody): String = SyncBodyCodec.encodePlain(body)

    private fun liveRecord(clientId: String, change: Long, encrypted: String, storedAt: String): TranscriptionRecord =
        TranscriptionRecord(clientId, change, false, 0, encrypted, storedAt)

    private fun deleteRecord(clientId: String, change: Long, storedAt: String): TranscriptionRecord =
        TranscriptionRecord(clientId, change, true, null, null, storedAt)

    private fun page(items: List<TranscriptionRecord>, next: String, more: Boolean): String {
        val itemStrings = items.map { r ->
            when (r.deleted) {
                true -> "{\"client_id\":\"${r.clientId}\",\"change\":${r.change},\"deleted\":true,\"stored_at\":\"${r.storedAt}\"}"
                false -> "{\"client_id\":\"${r.clientId}\",\"change\":${r.change},\"deleted\":false,\"key_version\":0,\"encrypted\":\"${r.encrypted}\",\"stored_at\":\"${r.storedAt}\"}"
            }
        }
        return "{\"items\":[${itemStrings.joinToString(",")}],\"next\":\"$next\",\"more\":$more}"
    }

    private fun runCycle(transport: PullTransport, cursors: RecordingCursorPort, applier: PullApplier): CycleReport =
        PullCycle(transport, cursors, applier).run()

    // ---- test 1: first list consumes both pages, then follows the list cursor ----
    @Test
    fun `first list consumes both pages then follows the list cursor`() {
        val encA = encryptedOf(body("hello", 1700000000000))
        val encB = encryptedOf(body("world", 1700000000000))
        val encC = encryptedOf(body("again", 1700000000000))
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(
                liveRecord(clientA, 1, encA, "2023-11-14T22:13:20.000Z"),
                liveRecord(clientB, 2, encB, "2023-11-14T22:13:20.000Z"),
            ), "1-1", true)),
            PullTransportResult.Answered(200, page(listOf(
                liveRecord(clientC, 3, encC, "2023-11-14T22:13:20.000Z"),
            ), "1-2", false)),
            PullTransportResult.Answered(200, page(listOf(), "1-3", false)),
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertEquals("first run stored three rows of two pages", 3, report.first!!.stored)
        assertTrue("first run pruned (full list)", report.first!!.pruned)
        assertEquals("follow-up stored none", 0, report.second!!.stored)
        assertFalse("follow-up does not prune (not a full list)", report.second!!.pruned)
        assertEquals("rows in page order", listOf(clientA, clientB, clientC), sink.rowIds())
        assertEquals("one prune only", 1, sink.retainKeeps().size)
        assertTrue("cursor saved", report.cursorSaved)
        assertNull("no refused reason", report.refusedReason)
        assertFalse("nothing cleared", report.cursorCleared)
        assertEquals("saves in order: 1-2 then 1-3",
            listOf("savePullCursor(1-2)", "savePullCursor(1-3)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
        assertEquals("stored ends at 1-3", "1-3", cursors.stored)
        assertEquals("three requests", 3, transport.requests.size)
        assertEquals("first request no since", "", transport.requests[0].query())
        assertEquals("second request since 1-1", "since=1-1", transport.requests[1].query())
        assertEquals("third request since 1-2", "since=1-2", transport.requests[2].query())
    }

    // ---- test 2: 410 on stored cursor, list-again once, second 410 clears cursor ----
    @Test
    fun `410 on stored cursor then list-again once ends refused and clears cursor`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(410, "cursor below floor"),
            PullTransportResult.Answered(410, "still below floor"),
        ))
        val cursors = RecordingCursorPort("c1")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertNull("no first apply", report.first)
        assertEquals("refused reason", "listed again once and refused again", report.refusedReason)
        assertTrue("cursor cleared", report.cursorCleared)
        assertEquals("clear call",
            listOf("clearPullCursor"),
            cursors.calls.filter { it.startsWith("clearPullCursor") })
        assertNull("stored cursor cleared", cursors.stored)
        assertEquals("two requests", 2, transport.requests.size)
        assertEquals("first request since c1", "since=c1", transport.requests[0].query())
        assertEquals("second request no since", "", transport.requests[1].query())
    }

    // ---- test 3: delete record reaches sink with exact ms; second empty answer repeats ----
    @Test
    fun `delete record reaches sink with exact ms and the follow-up is an empty page`() {
        val d = deleteRecord("1b0d5e2f-7b3c-4d4e-8f9a-2b3c4d5e6f70", 2, "2023-11-14T22:13:20.123Z")
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(d), "1-1", false)),
            PullTransportResult.Answered(200, page(listOf(), "1-1", false)),
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertEquals("delete once",
            listOf("1b0d5e2f-7b3c-4d4e-8f9a-2b3c4d5e6f70@1700000000123"),
            sink.deleteCalls())
        assertEquals("first deleted one", 1, report.first!!.deleted)
        assertEquals("first stored zero", 0, report.first!!.stored)
        assertEquals("two saves both 1-1",
            listOf("savePullCursor(1-1)", "savePullCursor(1-1)"),
            cursors.calls.filter { it.startsWith("savePullCursor") })
        assertEquals("stored ends at 1-1", "1-1", cursors.stored)
        assertEquals("two requests", 2, transport.requests.size)
        assertEquals("first request no since", "", transport.requests[0].query())
        assertEquals("second request since 1-1", "since=1-1", transport.requests[1].query())
    }

    // ---- test 4: queued live row skipped, prune keeps it, delete for third id reaches sink ----
    @Test
    fun `queued live row is skipped and prune keeps it`() {
        val encA = encryptedOf(body("hello", 1700000000000))
        val encB = encryptedOf(body("world", 1700000000000))
        val d2 = deleteRecord(clientC, 4, "2023-11-14T22:13:20.456Z")
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(
                liveRecord(clientA, 1, encA, "2023-11-14T22:13:20.000Z"),
                liveRecord(clientB, 2, encB, "2023-11-14T22:13:20.000Z"),
                d2,
            ), "1-1", false)),
            PullTransportResult.Answered(200, page(listOf(), "1-1", false)),
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queued.add(clientA)
        probe.queuedIdsList = listOf(clientA)
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertEquals("skipped queued one", 1, report.first!!.skippedQueued)
        assertEquals("stored one", 1, report.first!!.stored)
        assertEquals("row ids", listOf(clientB), sink.rowIds())
        assertEquals("delete ids",
            listOf("00000003-0000-4000-8000-000000000003@1700000000456"),
            sink.deleteCalls())
        assertEquals("retains once (only run1 fullList prunes)", 1, sink.retainKeeps().size)
        assertTrue("retain keeps queued id and stored id",
            sink.retainKeeps()[0].contains(clientA) && sink.retainKeeps()[0].contains(clientB))
        assertEquals("stored ends at 1-1", "1-1", cursors.stored)
    }

    // ---- test 5: 401 on first pull ends refused, nothing applied, cursor untouched ----
    @Test
    fun `401 on first pull ends refused and nothing changes`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(401, "unauthorized"),
        ))
        val cursors = RecordingCursorPort("c1")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertNull("no first apply", report.first)
        assertEquals("refused reason", "the server answered status 401", report.refusedReason)
        assertFalse("cursor not saved", report.cursorSaved)
        assertFalse("cursor not cleared", report.cursorCleared)
        assertEquals("stored cursor untouched", "c1", cursors.stored)
        assertTrue("sink rows empty", sink.rowIds().isEmpty())
        assertTrue("sink deletes empty", sink.deleteIds().isEmpty())
    }

    // ---- test 6: unreachable leaves cursor unchanged and applies nothing ----
    @Test
    fun `unreachable leaves cursor unchanged and applies nothing`() {
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Unreachable,
        ))
        val cursors = RecordingCursorPort("c1")
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        val report = runCycle(transport, cursors, applier)

        assertNull("no first apply", report.first)
        assertEquals("refused reason", "the server could not be reached", report.refusedReason)
        assertFalse("cursor not saved", report.cursorSaved)
        assertFalse("cursor not cleared", report.cursorCleared)
        assertEquals("stored cursor untouched", "c1", cursors.stored)
        assertTrue("sink rows empty", sink.rowIds().isEmpty())
        assertTrue("sink deletes empty", sink.deleteIds().isEmpty())
    }

    // ---- test 7: cursor saved only after apply; sink throws -> cursor not saved ----
    @Test
    fun `sink throw on apply means cursor is not saved`() {
        val encA = encryptedOf(body("hello", 1700000000000))
        val transport = ScriptedPullTransport(listOf(
            PullTransportResult.Answered(200, page(listOf(
                liveRecord(clientA, 1, encA, "2023-11-14T22:13:20.000Z"),
            ), "n1", false)),
        ))
        val cursors = RecordingCursorPort(null)
        val sink = RecordingPullSink().apply { throwOn = AssertionError("apply boom") }
        val probe = RecordingQueueProbe()
        val applier = PullApplier(sink, probe)

        var thrown: Throwable? = null
        try {
            runCycle(transport, cursors, applier)
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull("cycle threw", thrown)
        assertEquals("throw message", "apply boom", thrown!!.message)
        assertEquals("no save calls", 0, cursors.saveCalls)
        assertNull("cursor never stored", cursors.stored)
        assertEquals("only pullCursor call", listOf("pullCursor"), cursors.calls)
    }
}
