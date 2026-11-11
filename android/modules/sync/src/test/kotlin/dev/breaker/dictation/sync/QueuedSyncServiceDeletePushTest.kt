package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The delete drain: a delete queue row is sent with [SyncUploader.delete], never with [SyncUploader.upload]. */
class QueuedSyncServiceDeletePushTest {

    private fun newRig() = ServiceRig()

    /** A delete entry drains to DELETE; 204 is CONFIRMED and the row is removed. */
    @Test
    fun `a delete entry drains to DELETE and a 204 removes it`() {
        val rig = newRig()
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)
        rig.service.enqueueDelete(clientId(1))

        val report = rig.push()

        assertEquals("android_sync: the drain confirms the delete", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: the delete went to DELETE, not POST /v1/sync", listOf(clientId(1)), rig.uploader.deleted)
        assertTrue("android_sync: no push was uploaded", rig.uploader.received.isEmpty())
        assertEquals("android_sync: the delete row left the queue", emptyList<String>(), rig.database.queuedIds())
    }

    /** Rule 2: a delete removes a queued SAVE of the same id first, and the delete is still sent. */
    @Test
    fun `a delete removes a queued save of the same id first and the delete is still sent`() {
        val rig = newRig()
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)
        rig.queue(clientId(1)) // a save of the same id
        rig.service.enqueueDelete(clientId(1))

        val report = rig.push()

        assertEquals("android_sync: the save is gone and the delete confirms", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: rule 2: the delete is sent even though a save was queued", listOf(clientId(1)), rig.uploader.deleted)
        assertTrue("android_sync: the save was never uploaded", rig.uploader.received.isEmpty())
        assertEquals("android_sync: nothing is left queued", emptyList<String>(), rig.database.queuedIds())
    }

    /** A transient failure (401/503-shaped FAILED) keeps the delete entry queued and stops the drain. */
    @Test
    fun `a transient failure on the delete keeps the entry and stops the drain`() {
        val rig = newRig()
        rig.uploader.deleteScript(UploadOutcome.FAILED, UploadOutcome.CONFIRMED)
        rig.service.enqueueDelete(clientId(1))
        rig.service.enqueueDelete(clientId(2))

        val report = rig.push()

        assertEquals("android_sync: the first delete failed and the drain stopped", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: only the first delete was attempted", listOf(clientId(1)), rig.uploader.deleted)
        assertEquals("android_sync: the failed delete and the one behind it stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
    }

    /** The delete branch reuses the drain mutex: a second drain on a locked mutex returns at once. */
    @Test
    fun `a concurrent drain is turned away because the delete branch shares the drain mutex`() {
        val rig = newRig()
        val deleteStarted = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)
        rig.uploader.onDelete = { deleteStarted.countDown(); releaseDelete.await() }
        rig.service.enqueueDelete(clientId(1))

        val result = ArrayList<SyncReport>(1)
        val drainThread = Thread { result.add(rig.service.pushPending()) }
        drainThread.start()

        assertTrue("android_sync: the first drain reached the delete call", deleteStarted.await(10, TimeUnit.SECONDS))

        val turnedAway = rig.service.pushPending()
        assertEquals("android_sync: a drain on a locked mutex returns an empty report at once", SyncReport(0, 0), turnedAway)

        releaseDelete.countDown()
        drainThread.join(10_000)

        assertEquals("android_sync: the first drain confirmed the delete", SyncReport(1, 0, 0), result.single())
        assertEquals("android_sync: exactly one delete was sent", listOf(clientId(1)), rig.uploader.deleted)
        assertEquals("android_sync: the row left the queue once", emptyList<String>(), rig.database.queuedIds())
    }

    /** S1: a second enqueueDelete removes the queued delete first and re-inserts at the tail; no duplicate, no park. */
    @Test
    fun `a second enqueueDelete moves the delete to the tail and the drain deletes it once`() {
        val rig = newRig()
        rig.service.enqueueDelete(clientId(8))
        rig.service.enqueueDelete(clientId(8))

        assertEquals("android_sync: one delete row only", listOf(clientId(8)), rig.database.queuedIds())
        assertEquals("android_sync: nothing is parked", 0, rig.service.parkedCount())
        assertEquals("android_sync: the second enqueueDelete moved the row to the tail", 2L, rig.database.queued().single().seq)

        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)
        val report = rig.push()

        assertEquals("android_sync: the delete is sent exactly once", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: exactly one delete was uploaded", listOf(clientId(8)), rig.uploader.deleted)
        assertTrue("android_sync: the queue is empty after the drain", rig.database.queuedIds().isEmpty())
    }
}
