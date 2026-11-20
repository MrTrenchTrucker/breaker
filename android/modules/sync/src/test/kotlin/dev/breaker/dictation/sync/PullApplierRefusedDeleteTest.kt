package dev.breaker.dictation.sync

import dev.breaker.shared.api.TranscriptionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/** PullApplier counts a delete only when applyRemoteDelete accepts it; a refused delete is
 * counted in refusedDelete, never in deleted. UNCOMPILED: hand-traced, not run by any build. */
class PullApplierRefusedDeleteTest {

    private val clientD = "00000003-0000-4000-8000-000000000003"
    private val storedAt = "2023-11-14T22:13:20.000Z"
    private val deletedAtMs = 1_700_000_000_000L

    private fun aRecord(): TranscriptionRecord =
        TranscriptionRecord(
            clientId = clientD,
            change = 1L,
            deleted = true,
            keyVersion = 0,
            encrypted = null,
            storedAt = storedAt,
        )

    private fun anOkResult(vararg records: TranscriptionRecord): PullRunResult =
        PullRunResult(PullOutcome.Ok(records.toList(), "next"), fullList = false)

    /** A refused delete is not counted deleted and is counted refusedDelete; it still reaches the sink. */
    @Test
    fun `a refused delete is not counted deleted and is counted refusedDelete`() {
        val sink = RecordingPullSink()
        sink.deleteAnswer = false
        val probe = RecordingQueueProbe()
        val report = PullApplier(sink, probe).apply(anOkResult(aRecord()))
        assertEquals("android_sync: a refused delete does not count deleted", 0, report.deleted)
        assertEquals("android_sync: a refused delete counts refusedDelete", 1, report.refusedDelete)
        assertEquals("android_sync: the refused delete still reaches the sink",
            listOf(clientD), sink.deleteIds())
        assertEquals("android_sync: the deletedAt reaches the sink",
            deletedAtMs, sink.deletes.first().deletedAt)
    }

    /** An accepted delete still counts deleted and counts zero refusedDelete. */
    @Test
    fun `an accepted delete still counts deleted and zero refusedDelete`() {
        val sink = RecordingPullSink() // deleteAnswer defaults to true (PullApplierFakes.kt:20)
        val probe = RecordingQueueProbe()
        val report = PullApplier(sink, probe).apply(anOkResult(aRecord()))
        assertEquals("android_sync: an accepted delete counts deleted", 1, report.deleted)
        assertEquals("android_sync: an accepted delete counts zero refusedDelete", 0, report.refusedDelete)
    }
}