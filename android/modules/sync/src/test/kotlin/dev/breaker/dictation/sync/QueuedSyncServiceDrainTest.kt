package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What one `pushPending` call does with the queue: the order it drains in, when an entry is
 * removed, what each answer of the uploader means, and what the report counts.
 *
 * An entry removed too early is lost when the upload fails; an entry never removed is sent
 * again for ever; a wrong count tells the user something false about what left the phone.
 */
class QueuedSyncServiceDrainTest {

    private val rig = ServiceRig()

    /** A failure here means an empty queue is reported as a failure, or the uploader is called for nothing. */
    @Test
    fun `an empty queue gives a zero report and no upload`() {
        val report = rig.push()

        assertEquals("android_sync: an empty queue must give SyncReport(0, 0)", SyncReport(0, 0), report)
        rig.assertNothingUploadedOrRemoved("empty queue")
    }

    /** A failure here means a confirmed entry stays queued, or is removed without being counted. */
    @Test
    fun `a confirmed entry is removed and counted`() {
        rig.queue(clientId(1))

        val report = rig.push()

        assertEquals("android_sync: one confirmed entry must give SyncReport(1, 0)", SyncReport(1, 0), report)
        assertEquals("android_sync: the confirmed entry must be uploaded once", listOf(clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: the confirmed entry must be gone", emptyList<String>(), rig.database.queuedIds())
        assertEquals(
            "android_sync: the removal must run inside a transaction",
            listOf(true),
            rig.database.removesInsideTransaction,
        )
    }

    /** A failure here means the queue is drained in some order other than the order it was filled. */
    @Test
    fun `entries are uploaded oldest first`() {
        // createdAt runs backwards: the oldest entry is the first queued, not the one with the lowest time.
        rig.service.enqueue(aTranscription(clientId(1), createdAt = 900))
        rig.service.enqueue(aTranscription(clientId(2), createdAt = 800))
        rig.service.enqueue(aTranscription(clientId(3), createdAt = 700))

        rig.push()

        assertEquals(
            "android_sync: entries must be uploaded in the order they were queued",
            listOf(clientId(1), clientId(2), clientId(3)),
            rig.uploadedIds(),
        )
        assertEquals(
            "android_sync: the bodies must carry the createdAt of their entries",
            listOf(900L, 800L, 700L),
            rig.uploadedBodies().map { it.createdAtMs },
        )
    }

    /** A failure here means an entry can leave the queue before the uploader has said it arrived. */
    @Test
    fun `an entry is removed only after the uploader has answered CONFIRMED`() {
        rig.queue(clientId(1), clientId(2))
        val pendingAtCall = ArrayList<Int>()
        val stillQueuedAtCall = ArrayList<Boolean>()
        rig.uploader.onUpload = { sent ->
            pendingAtCall.add(rig.service.pendingCount())
            stillQueuedAtCall.add(sent.clientId in rig.database.queuedIds())
        }
        rig.log.clear()

        val report = rig.push()

        assertEquals("android_sync: the entry being uploaded must still be counted", listOf(2, 1), pendingAtCall)
        assertEquals("android_sync: the entry being uploaded must still be queued", listOf(true, true), stillQueuedAtCall)
        assertEquals(
            "android_sync: every upload must come before the removal of its entry",
            listOf("upload", "remove", "upload", "remove"),
            rig.log.events.filter { it == "upload" || it == "remove" },
        )
        assertEquals("android_sync: both entries must be pushed", SyncReport(2, 0), report)
        assertEquals("android_sync: after the call both entries are gone", 0, rig.service.pendingCount())
    }

    /** A failure here means a refused entry is dropped, the drain goes on past it, or the count is not one. */
    @Test
    fun `FAILED keeps the entry and the rest queued and counts one failed`() {
        rig.queue(clientId(1), clientId(2), clientId(3))
        rig.uploader.script(UploadOutcome.FAILED)

        val report = rig.push()

        assertEquals("android_sync: FAILED must give SyncReport(0, 1)", SyncReport(0, 1), report)
        assertEquals("android_sync: the drain must stop at the failure", listOf(clientId(1)), rig.uploadedIds())
        assertEquals(
            "android_sync: nothing may be removed, and the order must stay",
            listOf(clientId(1), clientId(2), clientId(3)),
            rig.database.queuedIds(),
        )
    }

    /** A failure here means entries confirmed before a failure are put back, or the rest lose their place. */
    @Test
    fun `a failure mid-drain keeps the confirmed entries removed and the rest queued in order`() {
        rig.queue(clientId(1), clientId(2), clientId(3), clientId(4))
        rig.uploader.script(UploadOutcome.CONFIRMED, UploadOutcome.CONFIRMED, UploadOutcome.FAILED)

        val report = rig.push()

        assertEquals("android_sync: two confirmed then a failure must give SyncReport(2, 1)", SyncReport(2, 1), report)
        assertEquals(
            "android_sync: the failed entry stays at the head, the rest behind it in order",
            listOf(clientId(3), clientId(4)),
            rig.database.queuedIds(),
        )
        assertEquals(
            "android_sync: the drain must stop at the failure",
            listOf(clientId(1), clientId(2), clientId(3)),
            rig.uploadedIds(),
        )
    }

    /** A failure here means an unreachable server is counted as a failure, removes an entry, or does not stop the drain. */
    @Test
    fun `UNREACHABLE from the uploader stops the drain and removes nothing and counts no failure`() {
        rig.queue(clientId(1), clientId(2), clientId(3))
        rig.uploader.script(UploadOutcome.UNREACHABLE)

        val report = rig.push()

        assertEquals("android_sync: UNREACHABLE must give SyncReport(0, 0)", SyncReport(0, 0), report)
        assertEquals("android_sync: the drain must stop at UNREACHABLE", listOf(clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: UNREACHABLE must remove nothing", 0, rig.database.removeCalls)
        assertEquals(
            "android_sync: all three entries must stay queued in order",
            listOf(clientId(1), clientId(2), clientId(3)),
            rig.database.queuedIds(),
        )
    }

    /** A failure here means the service uploads while the probe says the server is not there. */
    @Test
    fun `an unreachable probe gives a zero report and no upload`() {
        rig.queue(clientId(1), clientId(2))
        rig.log.clear()
        rig.probe.reachable = false

        val report = rig.push()

        assertEquals("android_sync: an unreachable probe must give SyncReport(0, 0)", SyncReport(0, 0), report)
        rig.assertNothingUploadedOrRemoved("unreachable probe")
        assertEquals("android_sync: the queue must stay as it was", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
        assertEquals("android_sync: with an unreachable probe the walk must not start", listOf("probe"), rig.log.events)
    }

    /** A failure here means an exception from the uploader escapes, or the entry is dropped or counted wrongly. */
    @Test
    fun `a throwing uploader counts as FAILED and keeps the entry`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(IllegalStateException("the uploader broke"))

        val report = rig.push()

        assertEquals("android_sync: a throwing uploader must give SyncReport(0, 1)", SyncReport(0, 1), report)
        assertEquals("android_sync: the drain must stop at the throw", listOf(clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: both entries must stay", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
    }

    /** A failure here means the counts of a mixed run are not the exact number of entries that left the queue. */
    @Test
    fun `the report counts are exact over a mixed drain`() {
        rig.queue(clientId(1), clientId(2), clientId(3), clientId(4), clientId(5))
        rig.uploader.script(
            UploadOutcome.CONFIRMED, UploadOutcome.CONFIRMED, UploadOutcome.FAILED,
            UploadOutcome.CONFIRMED, UploadOutcome.UNREACHABLE,
        )

        val first = rig.push()
        assertEquals("android_sync: two confirmed then FAILED", SyncReport(2, 1), first)
        assertEquals(
            "android_sync: three entries must remain",
            listOf(clientId(3), clientId(4), clientId(5)),
            rig.database.queuedIds(),
        )

        val second = rig.push()
        assertEquals("android_sync: one confirmed then UNREACHABLE", SyncReport(1, 0), second)
        assertEquals("android_sync: two entries must remain", listOf(clientId(4), clientId(5)), rig.database.queuedIds())

        val third = rig.push()
        assertEquals("android_sync: the rest must be confirmed", SyncReport(2, 0), third)
        assertEquals("android_sync: the queue must be empty at the end", 0, rig.service.pendingCount())
    }

    /** A failure here means the probe is asked more or less than once per call, or not before everything else. */
    @Test
    fun `the probe is asked once per call before anything else`() {
        rig.queue(clientId(1), clientId(2))
        rig.log.clear()

        rig.push()

        assertEquals("android_sync: the probe must be asked once per call", 1, rig.log.count("probe"))
        assertEquals("android_sync: the probe must be the first step", "probe", rig.log.events.first())
        assertEquals("android_sync: the probe must be asked once for the whole drain", 1, rig.probe.calls)

        rig.probe.reachable = false
        rig.queue(clientId(3))
        val mark = rig.log.events.size

        val report = rig.push()

        assertEquals("android_sync: with an unreachable probe nothing but the probe may run", listOf("probe"), rig.log.since(mark))
        assertEquals("android_sync: with an unreachable probe the report is zero", SyncReport(0, 0), report)
    }

    /** A failure here means a throw from the store is replaced by a quiet report, or the entry is lost. */
    @Test
    fun `a storage exception on remove after CONFIRMED propagates and the entry stays`() {
        rig.queue(clientId(1), clientId(2))
        val cause = IllegalStateException("the disk is gone")
        rig.database.failNextRemove(cause, afterWriting = false)

        val thrown = thrownBy { rig.push() }

        assertSame("android_sync: the very same failure must leave pushPending", cause, thrown)
        assertEquals("android_sync: the drain must stop at the failed removal", listOf(clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: both entries must stay", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
        assertEquals("android_sync: the failed removal must roll back", 1, rig.database.rollbacks)
    }

    /** B2: the drain removes its own row by seq, so a delete queued mid-drain survives this drain and is sent on the next drain. */
    @Test
    fun `the drain removes its own row by seq and a delete queued mid-drain is sent on the next drain`() {
        rig.uploader.script(UploadOutcome.CONFIRMED)
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)
        rig.uploader.onUpload = { sent -> rig.service.enqueueDelete(sent.clientId) }
        rig.service.enqueue(aTranscription(clientId(8), text = "the save"))

        val report = rig.push()

        assertEquals("android_sync: the save confirms and the drain stops at its bound", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: the delete queued mid-drain is still queued", listOf(clientId(8)), rig.database.queuedIds())
        assertEquals("android_sync: the surviving row is still a delete", SyncQueueSql.KIND_DELETE, rig.database.queued().single().kind)

        val second = rig.push()
        assertEquals("android_sync: the next drain sends the delete", SyncReport(1, 0, 0), second)
        assertEquals("android_sync: the delete went to DELETE, not POST", listOf(clientId(8)), rig.uploader.deleted)
        assertTrue("android_sync: nothing is left queued", rig.database.queuedIds().isEmpty())
    }

    /** SYNC_13: a delete row inserted via insertDeleteIfAbsent reads back kind delete, and the drain
     * sends DELETE, not an empty save. */
    @Test
    fun `a queued delete row reads back kind delete and the drain sends DELETE, not an empty save`() {
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)
        rig.service.enqueueDelete(clientId(7))

        assertEquals("android_sync: the delete row reads back kind delete",
            SyncQueueSql.KIND_DELETE, rig.database.queued().single().kind)
        assertEquals("android_sync: one delete row is queued", listOf(clientId(7)), rig.database.queuedIds())

        val report = rig.push()

        assertEquals("android_sync: the delete is confirmed", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: the drain sent DELETE, not a save upload", listOf(clientId(7)), rig.uploader.deleted)
        assertTrue("android_sync: no save was uploaded", rig.uploader.received.isEmpty())
        assertTrue("android_sync: the delete row is gone", rig.database.queuedIds().isEmpty())
    }

    /** SYNC_R2_B6: a delete answered with a non-permanent refusal (401, 409, 429, 503) stops the drain
     * with FAILED and does not park the row. */
    @Test
    fun `a non-permanent refusal on a delete stops the drain and parks nothing`() {
        for (status in listOf(401, 409, 429, 503)) {
            val r = ServiceRig()
            r.service.enqueueDelete(clientId(61))
            r.uploader.thenThrowDelete(PermanentRefusalException(status, "refused $status"))

            val report = r.push()

            assertEquals("android_sync: status $status stops the drain with FAILED", SyncReport(0, 1, 0), report)
            assertEquals("android_sync: status $status: the delete was attempted once", listOf(clientId(61)), r.uploader.deleted)
            assertTrue("android_sync: status $status: nothing was uploaded", r.uploader.received.isEmpty())
            assertEquals("android_sync: status $status: nothing is parked", 0, r.service.parkedCount())
            assertNull("android_sync: status $status: no parked reason", r.database.parkedReason(clientId(61)))
            assertEquals("android_sync: status $status: the row stays queued", listOf(clientId(61)), r.database.queuedIds())
        }
    }

    /** SYNC_R2_B6, other side: a delete answered with a permanent refusal (400, 413) is parked and
     * the drain goes on to the next row. */
    @Test
    fun `a permanent refusal on a delete parks it and the drain goes on`() {
        for (status in listOf(400, 413)) {
            val r = ServiceRig()
            r.service.enqueueDelete(clientId(61))
            r.service.enqueue(aTranscription(clientId(62), text = "behind the delete"))
            r.uploader.thenThrowDelete(PermanentRefusalException(status, "refused $status"))
            r.uploader.script(UploadOutcome.CONFIRMED)

            val report = r.push()

            assertEquals("android_sync: status $status parks the delete and sends the save", SyncReport(1, 0, 1), report)
            assertEquals("android_sync: status $status: delete attempted, save uploaded", listOf(clientId(62)), r.uploadedIds())
            assertEquals("android_sync: status $status: the delete went to DELETE", listOf(clientId(61)), r.uploader.deleted)
            assertEquals("android_sync: status $status: one row parked", 1, r.service.parkedCount())
            assertEquals("android_sync: status $status: the parked row stays and the save is gone", listOf(clientId(61)), r.database.queuedIds())
            assertEquals("android_sync: status $status: the parked reason", "refused $status", r.database.parkedReason(clientId(61)))
        }
    }
}
