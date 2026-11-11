package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `enqueue` promises: the entry is committed when the call returns, the same id is queued
 * once, a storage failure is heard about, and entries keep the order they came in.
 *
 * A queue that loses or reorders an entry, or hides a failed write, loses a dictation without
 * telling anyone.
 */
class QueuedSyncServiceEnqueueTest {

    private val rig = ServiceRig()

    /** A failure here means enqueue can return before its row is committed, so a crash right after loses it. */
    @Test
    fun `enqueue commits the entry before it returns`() {
        rig.service.enqueue(aTranscription(clientId(1)))

        assertEquals(
            "android_sync: the insert must run inside a transaction",
            listOf(true),
            rig.database.insertsInsideTransaction,
        )
        assertEquals("android_sync: exactly one transaction must have committed when enqueue returns", 1, rig.database.commits)
        assertFalse("android_sync: no transaction may still be open when enqueue returns", rig.database.insideTransaction)
        assertEquals("android_sync: the entry must be queued", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A failure here means a second copy of an id replaces the first, or is queued beside it. */
    @Test
    fun `a second enqueue of the same id keeps the first copy`() {
        rig.service.enqueue(aTranscription(clientId(1), text = "first words about quartz"))
        rig.service.enqueue(aTranscription(clientId(1), text = "second words about marmalade"))

        assertEquals("android_sync: one id must be queued once", listOf(clientId(1)), rig.database.queuedIds())
        assertEquals(
            "android_sync: the first copy must stay in the queue",
            "first words about quartz",
            rig.database.queued().single().text,
        )
        rig.push()
        assertEquals(
            "android_sync: the first copy must be the one that is sent",
            listOf("first words about quartz"),
            rig.uploadedBodies().map { it.text },
        )
        assertEquals("android_sync: one record must be uploaded for one id", listOf(clientId(1)), rig.uploadedIds())
    }

    /** A failure here means a failed write is hidden from the caller, or leaves half a row behind. */
    @Test
    fun `a storage failure on enqueue propagates and leaves nothing queued`() {
        val cause = IllegalStateException("disk full")
        // The row is stored first and the failure comes after: only the transaction can take it back.
        rig.database.failNextInsert(cause, afterWriting = true)

        val thrown = thrownBy { rig.service.enqueue(aTranscription(clientId(1))) }

        assertNotNull("android_sync: the storage failure must reach the caller of enqueue", thrown)
        assertSame("android_sync: the very same failure must propagate, unchanged", cause, thrown)
        assertEquals("android_sync: a failed enqueue must leave nothing queued", emptyList<String>(), rig.database.queuedIds())
        assertEquals("android_sync: a failed enqueue must not commit", 0, rig.database.commits)
        assertEquals("android_sync: a failed enqueue must roll back", 1, rig.database.rollbacks)
        assertEquals("android_sync: the queue must count nothing", 0, rig.service.pendingCount())
    }

    /** A failure here means the queue does not drain in the order it was filled. */
    @Test
    fun `entries come back in the order they were enqueued`() {
        // createdAt runs backwards: the order is the order of enqueue, not the order of time.
        rig.service.enqueue(aTranscription(clientId(1), createdAt = 3_000))
        rig.service.enqueue(aTranscription(clientId(2), createdAt = 2_000))
        rig.service.enqueue(aTranscription(clientId(3), createdAt = 1_000))

        val report = rig.push()

        assertEquals("android_sync: all three must be pushed", SyncReport(3, 0), report)
        assertEquals(
            "android_sync: the queue must drain oldest first",
            listOf(clientId(1), clientId(2), clientId(3)),
            rig.uploadedIds(),
        )
    }

    /** A failure here means silence, which transcribes to nothing, is refused or dropped instead of queued. */
    @Test
    fun `blank text is queued like any other text`() {
        rig.service.enqueue(aTranscription(clientId(1), text = ""))
        rig.service.enqueue(aTranscription(clientId(2), text = "   "))

        assertEquals(
            "android_sync: both blank entries must be queued",
            listOf(clientId(1), clientId(2)),
            rig.database.queuedIds(),
        )
        val report = rig.push()

        assertEquals("android_sync: both blank entries must be pushed", SyncReport(2, 0), report)
        assertEquals(
            "android_sync: the blank texts must be sent as they are",
            listOf("", "   "),
            rig.uploadedBodies().map { it.text },
        )
    }

    /** A failure here means the pending count is wrong, so the app cannot tell whether anything waits. */
    @Test
    fun `pendingCount counts the queued entries`() {
        assertEquals("android_sync: an empty queue counts 0", 0, rig.service.pendingCount())

        rig.queue(clientId(1), clientId(2))
        assertEquals("android_sync: two queued entries count 2", 2, rig.service.pendingCount())

        rig.uploader.script(UploadOutcome.CONFIRMED, UploadOutcome.UNREACHABLE)
        val report = rig.push()

        assertEquals("android_sync: one entry confirmed, then the server went away", SyncReport(1, 0), report)
        assertEquals("android_sync: one confirmed push leaves 1 queued", 1, rig.service.pendingCount())
        assertEquals("android_sync: the entry left must be the second", listOf(clientId(2)), rig.database.queuedIds())
    }

    /** B3 (a): a save of an id queued as a delete replaces the delete and is uploaded as a save. */
    @Test
    fun `a save replaces a queued delete of the same id and is uploaded as a save`() {
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)
        rig.uploader.script(UploadOutcome.CONFIRMED)
        rig.service.enqueueDelete(clientId(8))
        rig.service.enqueue(aTranscription(clientId(8), text = "saved after the delete"))

        val report = rig.push()

        assertEquals("android_sync: one row, the save, is confirmed", SyncReport(1, 0, 0), report)
        assertEquals("android_sync: the save was uploaded, not the delete", listOf(clientId(8)), rig.uploadedIds())
        assertTrue("android_sync: no delete was sent", rig.uploader.deleted.isEmpty())
        assertEquals(
            "android_sync: the saved text is what went up", "saved after the delete", rig.uploadedBodies().single().text,
        )
        assertTrue("android_sync: nothing is left queued", rig.database.queuedIds().isEmpty())
    }

    /** B3 (b): a delete of an id queued as a save still removes the save and is sent (rule 2, unchanged). */
    @Test
    fun `a delete of a queued save still removes the save and is sent`() {
        rig.uploader.script(UploadOutcome.CONFIRMED)
        rig.uploader.deleteScript(UploadOutcome.CONFIRMED)
        rig.service.enqueue(aTranscription(clientId(9), text = "saved first"))
        rig.service.enqueueDelete(clientId(9))

        val report = rig.push()

        assertEquals("android_sync: the delete confirms and the save is gone", SyncReport(1, 0, 0), report)
        assertTrue("android_sync: the save was never uploaded", rig.uploader.received.isEmpty())
        assertEquals("android_sync: the delete was sent", listOf(clientId(9)), rig.uploader.deleted)
        assertTrue("android_sync: nothing is left queued", rig.database.queuedIds().isEmpty())
    }

    /** A4: a second enqueueDelete keeps one delete row but moves it to the tail. */
    @Test
    fun `a second enqueueDelete keeps one delete row and moves it to the tail`() {
        rig.service.enqueueDelete(clientId(81))
        rig.service.enqueueDelete(clientId(82))
        rig.service.enqueueDelete(clientId(81))

        assertEquals("android_sync: one delete row per id, the re-enqueued one moves to the tail",
            listOf(clientId(82), clientId(81)), rig.database.queuedIds())
        assertEquals("android_sync: one delete row per id", 2, rig.service.pendingCount())
        val tail = rig.database.queued().last()
        assertEquals("android_sync: the re-enqueued delete moved to the tail", clientId(81), tail.clientId)
        assertEquals("android_sync: the tail row is a delete", SyncQueueSql.KIND_DELETE, tail.kind)
    }
}
