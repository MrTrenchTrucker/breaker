package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.PullSink
import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for PullApplier: one pull result applied to the PullSink port. */
class PullApplierTest {

    private val clientA = "00000001-0000-4000-8000-000000000001"
    private val clientB = "00000002-0000-4000-8000-000000000002"
    private val clientD = "00000003-0000-4000-8000-000000000003"
    private val storedAt = "2023-11-14T22:13:20.000Z"
    private val deletedAtMs = 1_700_000_000_000L

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

    private fun aBody(
        text: String = "hello world",
        source: SyncSource = SyncSource.LOCAL,
        model: String = "small.en",
        durationMs: Long = 1_200,
        createdAtMs: Long = 1_700_000_000_000L,
    ): SyncBody = SyncBody(text, source, model, durationMs, createdAtMs)

    private fun encryptedOf(body: SyncBody): String = SyncBodyCodec.encodePlain(body)

    private fun anOkResult(vararg records: TranscriptionRecord, fullList: Boolean = false): PullRunResult =
        PullRunResult(PullOutcome.Ok(records.toList(), "next"), fullList)

    private fun applier(sink: RecordingPullSink, probe: RecordingQueueProbe): PullApplier =
        PullApplier(sink, probe)

    /** What [block] threw, or null when it returned. An [Error] counts. */
    private fun thrownBy(block: () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }

    // ---- rule 1 ----

    /** A failure here means a non-Ok outcome makes no call and returns an all-zero report. */
    @Test
    fun `a not ok outcome is all zero and makes no call`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val report = applier(sink, probe).apply(PullRunResult(PullOutcome.Gone, fullList = true))
        assertEquals("android_sync: not ok means zero stored", 0, report.stored)
        assertEquals("android_sync: not ok means zero refusedByTombstone", 0, report.refusedByTombstone)
        assertEquals("android_sync: not ok means zero skippedQueued", 0, report.skippedQueued)
        assertEquals("android_sync: not ok means zero deleted", 0, report.deleted)
        assertEquals("android_sync: not ok means zero unreadable", 0, report.unreadable)
        assertEquals("android_sync: not ok means zero removedByPrune", 0, report.removedByPrune)
        assertEquals("android_sync: not ok means pruned false", false, report.pruned)
        assertEquals("android_sync: not ok touches the sink never", emptyList<String>(), sink.rowIds())
        assertEquals("android_sync: not ok touches the probe never", 0, probe.isQueuedCalls)
        // Accept control: an Ok outcome with no records still prunes when fullList.
        val okEmpty = applier(sink, probe).apply(anOkResult(fullList = true))
        assertEquals("android_sync: accept control: ok empty full list prunes", true, okEmpty.pruned)
    }

    /** A failure here means every not-ok outcome kind is treated alike. */
    @Test
    fun `every not ok outcome kind makes no call`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val outcomes = listOf(
            PullOutcome.BadCursor,
            PullOutcome.Gone,
            PullOutcome.Other(500),
            PullOutcome.Unreachable,
            PullOutcome.Refused("the list was refused"),
        )
        for (outcome in outcomes) {
            val report = applier(sink, probe).apply(PullRunResult(outcome, fullList = true))
            assertEquals("android_sync: $outcome is all zero", 0, report.stored)
            assertEquals("android_sync: $outcome pruned false", false, report.pruned)
        }
        assertEquals("android_sync: no call across all not-ok kinds", 0, sink.rowIds().size)
        assertEquals("android_sync: probe never touched", 0, probe.isQueuedCalls)
        // Accept control: a fresh applier with Ok stores.
        val freshSink = RecordingPullSink()
        val freshProbe = RecordingQueueProbe()
        val stored = applier(freshSink, freshProbe).apply(
            anOkResult(aRecord(clientA, encrypted = encryptedOf(aBody())), fullList = false))
        assertEquals("android_sync: accept control: ok stores one row", 1, stored.stored)
    }

    // ---- rules 2 and 4 ----

    /** A failure here means a readable live row is stored. */
    @Test
    fun `a readable live row is stored`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val body = aBody()
        val record = aRecord(clientA, encrypted = encryptedOf(body))
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: a stored row counts stored", 1, report.stored)
        assertEquals("android_sync: the row id reaches the sink", listOf(clientA), sink.rowIds())
        assertEquals("android_sync: no prune when not full list", false, report.pruned)
        // Accept control: a second readable row is stored too.
        val sink2 = RecordingPullSink()
        val probe2 = RecordingQueueProbe()
        val record2 = aRecord(clientB, encrypted = encryptedOf(aBody(text = "two")))
        val report2 = applier(sink2, probe2).apply(anOkResult(record2, fullList = false))
        assertEquals("android_sync: accept control: one row stores one", 1, report2.stored)
        assertEquals(
            "android_sync: accept control: second row reaches sink2",
            listOf(clientB), sink2.rowIds())
    }

    /** A failure here means a row refused by a tombstone counts refusedByTombstone. */
    @Test
    fun `a refused row counts refusedByTombstone`() {
        val sink = RecordingPullSink()
        sink.rowAnswer = false
        val probe = RecordingQueueProbe()
        val body = aBody()
        val record = aRecord(clientA, encrypted = encryptedOf(body))
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: a refused row counts refusedByTombstone", 1, report.refusedByTombstone)
        assertEquals("android_sync: a refused row does not count stored", 0, report.stored)
        assertEquals("android_sync: the row still reaches the sink", listOf(clientA), sink.rowIds())
        // Accept control: with rowAnswer true it counts stored.
        val sink2 = RecordingPullSink()
        val report2 = applier(sink2, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: accept control: accepted counts stored", 1, report2.stored)
    }

    /** A failure here means a queued live row is skipped and its body is never read. */
    @Test
    fun `a queued live row is skipped and its body is not read`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queued.add(clientA)
        val body = aBody()
        val record = aRecord(clientA, encrypted = encryptedOf(body))
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: a queued row counts skippedQueued", 1, report.skippedQueued)
        assertEquals("android_sync: a queued row stores nothing", 0, report.stored)
        assertEquals("android_sync: a queued row reaches the sink never", emptyList<String>(), sink.rowIds())
        assertEquals("android_sync: the probe is asked once", 1, probe.isQueuedCalls)
        // Accept control: the same row not queued is read and stored.
        val probe2 = RecordingQueueProbe()
        val report2 = applier(sink, probe2).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: accept control: not queued stores the row", 1, report2.stored)
    }

    /** A failure here means a queued row whose body would be unreadable still counts skipped. */
    @Test
    fun `a queued row with an unreadable body still counts skipped`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queued.add(clientA)
        val record = aRecord(clientA, keyVersion = 1, encrypted = encryptedOf(aBody()))
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: unreadable but queued counts skippedQueued", 1, report.skippedQueued)
        assertEquals("android_sync: unreadable but queued stores nothing", 0, report.stored)
        assertEquals("android_sync: unreadable but queued is not unreadable", 0, report.unreadable)
        assertEquals("android_sync: the sink is never called", emptyList<String>(), sink.rowIds())
        // Accept control: the same record not queued counts unreadable.
        val probe2 = RecordingQueueProbe()
        val report2 = applier(sink, probe2).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: accept control: not queued counts unreadable", 1, report2.unreadable)
    }

    // ---- rule 3 ----

    /** A failure here means a delete record is always handed to applyRemoteDelete. */
    @Test
    fun `a delete record is always applied`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queued.add(clientD)
        val record = aRecord(clientD, deleted = true, keyVersion = 0, encrypted = null)
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: a delete counts deleted", 1, report.deleted)
        assertEquals("android_sync: a delete reaches the sink even when queued",
            listOf(clientD), sink.deleteIds())
        assertEquals("android_sync: a delete storedAt parses to deletedAtMs",
            deletedAtMs, sink.deletes.first().deletedAt)
        // Accept control: a delete not queued is applied the same way.
        val probe2 = RecordingQueueProbe()
        val report2 = applier(sink, probe2).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: accept control: delete not queued still applied", 1, report2.deleted)
    }

    /** A failure here means an unparseable storedAt on a delete counts unreadable and calls nothing. */
    @Test
    fun `an unparseable storedAt on a delete is unreadable`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val record = aRecord(clientD, deleted = true, storedAt = "not-a-date")
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: bad storedAt counts unreadable", 1, report.unreadable)
        assertEquals("android_sync: bad storedAt makes no delete call", emptyList<String>(), sink.deleteIds())
        assertEquals("android_sync: bad storedAt does not count deleted", 0, report.deleted)
        // Accept control: a parseable storedAt deletes.
        val record2 = aRecord(clientD, deleted = true)
        val report2 = applier(sink, probe).apply(anOkResult(record2, fullList = false))
        assertEquals("android_sync: accept control: parseable deletes", 1, report2.deleted)
    }

    /** A failure here means a whole-second storedAt on a delete (no fraction digits) is read as a
     * time: PullApplier takes deleted_at from stored_at, and parseCreatedAt needs exactly three
     * fraction digits, so the record counts unreadable and reaches no sink call. */
    @Test
    fun `a delete with whole-second storedAt is unreadable`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val record = aRecord(clientD, deleted = true, storedAt = "2023-11-14T22:13:20Z")
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: whole-second storedAt counts unreadable", 1, report.unreadable)
        assertEquals("android_sync: whole-second storedAt reaches no sink", emptyList<String>(), sink.deleteIds())
        assertEquals("android_sync: whole-second storedAt counts no delete", 0, report.deleted)
    }

    /** A failure here means a key version 1 live row counts unreadable and calls nothing. */
    @Test
    fun `a key version 1 live row is unreadable`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val record = aRecord(clientA, keyVersion = 1, encrypted = encryptedOf(aBody()))
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: key version 1 counts unreadable", 1, report.unreadable)
        assertEquals("android_sync: key version 1 stores nothing", 0, report.stored)
        assertEquals("android_sync: key version 1 reaches the sink never", emptyList<String>(), sink.rowIds())
        // Accept control: key version 0 of the same body stores.
        val record2 = aRecord(clientA, keyVersion = 0, encrypted = encryptedOf(aBody()))
        val report2 = applier(sink, probe).apply(anOkResult(record2, fullList = false))
        assertEquals("android_sync: accept control: key version 0 stores", 1, report2.stored)
    }

    /** A failure here means a blank model on the body makes the row unreadable. */
    @Test
    fun `a blank model makes the row unreadable`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val body = aBody(model = "")
        val record = aRecord(clientA, encrypted = encryptedOf(body))
        val report = applier(sink, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: blank model counts unreadable", 1, report.unreadable)
        assertEquals("android_sync: blank model stores nothing", 0, report.stored)
        assertEquals("android_sync: blank model reaches the sink never", emptyList<String>(), sink.rowIds())
        // Accept control: a non-blank model stores.
        val record2 = aRecord(clientA, encrypted = encryptedOf(aBody(model = "small.en")))
        val report2 = applier(sink, probe).apply(anOkResult(record2, fullList = false))
        assertEquals("android_sync: accept control: non-blank model stores", 1, report2.stored)
    }

    /** A failure here means a field of the pulled body is lost or mixed up on the way to the sink. */
    @Test
    fun `a stored row carries every field of its body`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val body = aBody(
            text = "spoken words", source = SyncSource.SERVER, model = "base.en",
            durationMs = 2_300, createdAtMs = 1_700_000_123_000L,
        )
        applier(sink, probe).apply(
            anOkResult(aRecord(clientA, encrypted = encryptedOf(body)), fullList = false))
        assertEquals(
            "android_sync: every body field reaches the sink",
            Transcription(
                id = clientA, text = "spoken words", source = TranscriptionSource.SERVER,
                model = "base.en", durationMs = 2_300, createdAt = 1_700_000_123_000L,
            ),
            sink.rows.single().row,
        )
        // Accept control: the default body is a local one and keeps its own values.
        val sink2 = RecordingPullSink()
        applier(sink2, RecordingQueueProbe()).apply(
            anOkResult(aRecord(clientA, encrypted = encryptedOf(aBody())), fullList = false))
        assertEquals(
            "android_sync: accept control: a local body stays local",
            Transcription(
                id = clientA, text = "hello world", source = TranscriptionSource.LOCAL,
                model = "small.en", durationMs = 1_200, createdAt = 1_700_000_000_000L,
            ),
            sink2.rows.single().row,
        )
    }

    /** A failure here means calls on a mixed page keep the record order. */
    @Test
    fun `calls on a mixed page keep record order`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        val bodyA = aBody(text = "first")
        val bodyC = aBody(text = "third")
        val recordA = aRecord(clientA, encrypted = encryptedOf(bodyA))
        val recordB = aRecord(clientB, deleted = true)
        val recordC = aRecord("00000004-0000-4000-8000-000000000004", encrypted = encryptedOf(bodyC))
        val report = applier(sink, probe).apply(anOkResult(recordA, recordB, recordC, fullList = false))
        assertEquals("android_sync: stored ids in order",
            listOf(clientA, "00000004-0000-4000-8000-000000000004"), sink.rowIds())
        assertEquals("android_sync: the delete id", listOf(clientB), sink.deleteIds())
        assertEquals("android_sync: mixed page counts", 2, report.stored)
        assertEquals("android_sync: mixed page deleted count", 1, report.deleted)
        // Accept control: reversing the record order reverses the calls.
        val sink2 = RecordingPullSink()
        val probe2 = RecordingQueueProbe()
        applier(sink2, probe2).apply(anOkResult(recordB, recordA, fullList = false))
        assertEquals("android_sync: accept control: reversed order", listOf(clientA), sink2.rowIds())
        assertEquals("android_sync: accept control: reversed delete", listOf(clientB), sink2.deleteIds())
    }

    // ---- rule 6 ----

    /** A failure here means a throw from the sink reaches the caller. */
    @Test
    fun `a throw from the sink propagates`() {
        val sink = RecordingPullSink()
        sink.throwOn = RuntimeException("sink down")
        val probe = RecordingQueueProbe()
        val body = aBody()
        val record = aRecord(clientA, encrypted = encryptedOf(body))
        val failure = thrownBy { applier(sink, probe).apply(anOkResult(record, fullList = false)) }
        assertNotNull("android_sync: a sink throw must propagate", failure)
        assertTrue("android_sync: the sink throw is a RuntimeException", failure is RuntimeException)
        // Accept control: without a throw the call returns normally.
        val sink2 = RecordingPullSink()
        val report = applier(sink2, probe).apply(anOkResult(record, fullList = false))
        assertEquals("android_sync: accept control: no throw stores normally", 1, report.stored)
    }

    /** A failure here means a throw from the probe reaches the caller. */
    @Test
    fun `a throw from the probe propagates`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queued.add(clientA)
        val body = aBody()
        probe.isQueuedCalls = 0
        val throwingProbe = object : QueueProbe {
            override fun isQueued(clientId: String): Boolean = throw RuntimeException("probe down")
            override fun queuedIds(): List<String> = emptyList()
        }
        val record = aRecord(clientA, encrypted = encryptedOf(body))
        val failure = thrownBy {
            PullApplier(sink, throwingProbe).apply(anOkResult(record, fullList = false))
        }
        assertNotNull("android_sync: a probe throw must propagate", failure)
        assertTrue("android_sync: the probe throw is a RuntimeException", failure is RuntimeException)
        // Accept control: a non-throwing probe returns normally.
        val probe2 = RecordingQueueProbe()
        val record2 = aRecord(clientB, encrypted = encryptedOf(body))
        val report = applier(sink, probe2).apply(anOkResult(record2, fullList = false))
        assertEquals("android_sync: accept control: normal probe stores", 1, report.stored)
    }
}
