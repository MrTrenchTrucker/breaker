package dev.breaker.dictation.sync

import dev.breaker.shared.api.TranscriptionRecord
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for PullApplier rule 5: the prune after a finished full list. */
class PullApplierPruneTest {

    private val clientA = "00000001-0000-4000-8000-000000000001"
    private val clientB = "00000002-0000-4000-8000-000000000002"
    private val clientQ = "00000009-0000-4000-8000-000000000009"

    private fun aRecord(
        clientId: String,
        deleted: Boolean = false,
        keyVersion: Int? = 0,
        encrypted: String? = null,
    ): TranscriptionRecord =
        TranscriptionRecord(
            clientId = clientId,
            change = 1L,
            deleted = deleted,
            keyVersion = keyVersion,
            encrypted = encrypted,
            storedAt = "2023-11-14T22:13:20.000Z",
        )

    private fun aBody(text: String = "hello world"): SyncBody =
        SyncBody(text, SyncSource.LOCAL, "small.en", 1_200, 1_700_000_000_000L)

    private fun encryptedOf(body: SyncBody): String = SyncBodyCodec.encodePlain(body)

    private fun anOkResult(vararg records: TranscriptionRecord): PullRunResult =
        PullRunResult(PullOutcome.Ok(records.toList(), "next"), fullList = true)

    // ---- rule 5 ----

    /** A failure here means a finished full list prunes once with keep = list ids + queued ids. */
    @Test
    fun `a finished full list prunes once`() {
        val sink = RecordingPullSink()
        sink.retainAnswer = 3
        val probe = RecordingQueueProbe()
        probe.queuedIdsList = listOf(clientQ)
        val report = PullApplier(sink, probe).apply(anOkResult(aRecord(clientA), aRecord(clientB)))
        assertEquals("android_sync: prune sets pruned true", true, report.pruned)
        assertEquals("android_sync: prune sets removedByPrune", 3, report.removedByPrune)
        assertEquals("android_sync: retainOnly called once", 1, sink.retains.size)
        assertEquals("android_sync: keep is list ids plus queued ids",
            setOf(clientA, clientB, clientQ), sink.retainKeeps().first())
        // Accept control: not full list does not prune.
        val sink2 = RecordingPullSink()
        val probe2 = RecordingQueueProbe()
        val report2 = PullApplier(sink2, probe2).apply(PullRunResult(
            PullOutcome.Ok(listOf(aRecord(clientA)), "next"), fullList = false))
        assertEquals("android_sync: accept control: not full list pruned false", false, report2.pruned)
        assertEquals("android_sync: accept control: not full list no retain", 0, sink2.retains.size)
    }

    /** A failure here means a queued id not in the list stays in keep. */
    @Test
    fun `a queued id not in the list stays in keep`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queuedIdsList = listOf(clientQ)
        val report = PullApplier(sink, probe).apply(anOkResult(aRecord(clientA)))
        assertEquals("android_sync: keep holds the queued id too",
            setOf(clientA, clientQ), sink.retainKeeps().first())
        assertEquals("android_sync: pruned true", true, report.pruned)
        // Accept control: the queued id alone is kept when the list is empty.
        val sink2 = RecordingPullSink()
        val probe2 = RecordingQueueProbe()
        probe2.queuedIdsList = listOf(clientQ)
        PullApplier(sink2, probe2).apply(anOkResult())
        assertEquals("android_sync: accept control: empty list keeps queued only",
            setOf(clientQ), sink2.retainKeeps().first())
    }

    /** A failure here means skipped and unreadable ids are in keep. */
    @Test
    fun `skipped and unreadable ids are in keep`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queued.add(clientA)
        val bodyB = aBody(text = "bad")
        val recordB = aRecord(clientB, keyVersion = 1, encrypted = encryptedOf(bodyB))
        val report = PullApplier(sink, probe).apply(anOkResult(aRecord(clientA), recordB))
        assertEquals("android_sync: keep holds skipped and unreadable",
            setOf(clientA, clientB), sink.retainKeeps().first())
        assertEquals("android_sync: skipped queued", 1, report.skippedQueued)
        assertEquals("android_sync: unreadable", 1, report.unreadable)
        assertEquals("android_sync: pruned true", true, report.pruned)
        // Accept control: keep includes ids the list does not hold but the queue does.
        val sink2 = RecordingPullSink()
        val probe2 = RecordingQueueProbe()
        probe2.queuedIdsList = listOf(clientQ)
        PullApplier(sink2, probe2).apply(anOkResult(aRecord(clientA)))
        assertEquals("android_sync: accept control: queued id not in list is kept",
            setOf(clientA, clientQ), sink2.retainKeeps().first())
    }

    /** A failure here means a list holding only deletes keeps queued ids only. */
    @Test
    fun `a list holding only deletes keeps queued ids only`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queuedIdsList = listOf(clientQ)
        val recordD = aRecord(clientB, deleted = true)
        val report = PullApplier(sink, probe).apply(anOkResult(recordD))
        assertEquals("android_sync: deletes only keeps queued ids", setOf(clientQ),
            sink.retainKeeps().first())
        assertEquals("android_sync: deletes only counts deleted", 1, report.deleted)
        assertEquals("android_sync: deletes only pruned true", true, report.pruned)
        // Accept control: deletes with no queued ids keeps an empty set.
        val sink2 = RecordingPullSink()
        val probe2 = RecordingQueueProbe()
        PullApplier(sink2, probe2).apply(anOkResult(recordD))
        assertEquals("android_sync: accept control: deletes no queue keeps empty set",
            emptySet<String>(), sink2.retainKeeps().first())
    }

    /** A failure here means an empty full list keeps queued ids and prunes. */
    @Test
    fun `an empty full list keeps queued ids and prunes`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queuedIdsList = listOf(clientQ)
        sink.retainAnswer = 5
        val report = PullApplier(sink, probe).apply(anOkResult())
        assertEquals("android_sync: empty list keeps queued ids", setOf(clientQ), sink.retainKeeps().first())
        assertEquals("android_sync: empty list pruned true", true, report.pruned)
        assertEquals("android_sync: empty list removedByPrune", 5, report.removedByPrune)
        // Accept control: empty list, empty queue keeps empty set.
        val sink2 = RecordingPullSink()
        val probe2 = RecordingQueueProbe()
        val report2 = PullApplier(sink2, probe2).apply(anOkResult())
        assertEquals("android_sync: accept control: empty everything keeps empty set",
            emptySet<String>(), sink2.retainKeeps().first())
        assertEquals("android_sync: accept control: empty everything pruned true", true, report2.pruned)
    }

    /** A failure here means fullList false runs no retainOnly. */
    @Test
    fun `fullList false runs no retainOnly`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queuedIdsList = listOf(clientQ)
        val report = PullApplier(sink, probe).apply(PullRunResult(
            PullOutcome.Ok(listOf(aRecord(clientA)), "next"), fullList = false))
        assertEquals("android_sync: not full list no retain", 0, sink.retains.size)
        assertEquals("android_sync: not full list pruned false", false, report.pruned)
        // Accept control: same page fullList true retains.
        val sink2 = RecordingPullSink()
        val probe2 = RecordingQueueProbe()
        probe2.queuedIdsList = listOf(clientQ)
        val report2 = PullApplier(sink2, probe2).apply(PullRunResult(
            PullOutcome.Ok(listOf(aRecord(clientA)), "next"), fullList = true))
        assertEquals("android_sync: accept control: full list retains once", 1, sink2.retains.size)
        assertEquals("android_sync: accept control: full list pruned true", true, report2.pruned)
    }

    /** A failure here means a not-ok outcome with fullList true runs no retainOnly. */
    @Test
    fun `a not ok outcome runs no retainOnly even when fullList`() {
        val sink = RecordingPullSink()
        val probe = RecordingQueueProbe()
        probe.queuedIdsList = listOf(clientQ)
        val report = PullApplier(sink, probe).apply(PullRunResult(
            PullOutcome.BadCursor, fullList = true))
        assertEquals("android_sync: not ok no retain", 0, sink.retains.size)
        assertEquals("android_sync: not ok pruned false", false, report.pruned)
        assertEquals("android_sync: not ok all zero", 0, report.stored)
        // Accept control: same outcome fullList false also no retain.
        PullApplier(sink, probe).apply(PullRunResult(PullOutcome.BadCursor, fullList = false))
        assertEquals("android_sync: accept control: not ok either way no retain", 0, sink.retains.size)
    }

    /** A failure here means the retainOnly answer lands in removedByPrune. */
    @Test
    fun `the retainOnly answer lands in removedByPrune`() {
        val sink = RecordingPullSink()
        sink.retainAnswer = 7
        val probe = RecordingQueueProbe()
        val report = PullApplier(sink, probe).apply(anOkResult(aRecord(clientA)))
        assertEquals("android_sync: removedByPrune is the answer", 7, report.removedByPrune)
        assertEquals("android_sync: pruned true", true, report.pruned)
        // Accept control: a different answer lands differently.
        val sink2 = RecordingPullSink()
        sink2.retainAnswer = 0
        val report2 = PullApplier(sink2, probe).apply(anOkResult(aRecord(clientA)))
        assertEquals("android_sync: accept control: zero answer is zero", 0, report2.removedByPrune)
    }
}
