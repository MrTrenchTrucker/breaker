package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What a later `pushPending` sends after an earlier call stopped. Every push is built at key
 * version 0, so the server tells a retry from a new record by the pair of client id and key version
 * (ADR-006), and a retry always sends the same pair.
 *
 * A retry that changes the id, or a key version that moves between sends, either duplicates a
 * record on the server or sends a record the server cannot match to the first one.
 */
class QueuedSyncServiceDrainRetryTest {

    /** A failure here means a retry after FAILED is sent under a new id or a different key version. */
    @Test
    fun `a retry after FAILED sends the same client id at key version zero`() {
        val rig = ServiceRig()
        rig.queue(clientId(1))
        rig.uploader.script(UploadOutcome.FAILED)

        val first = rig.push()
        val second = rig.push()

        assertEquals("android_sync: the first call must fail once", SyncReport(0, 1), first)
        assertEquals("android_sync: the retry must push the entry", SyncReport(1, 0), second)
        assertEquals(
            "android_sync: both sends must carry the transcription id",
            listOf(clientId(1), clientId(1)),
            rig.uploadedIds(),
        )
        assertEquals(
            "android_sync: both sends must carry key version 0",
            listOf(0, 0),
            rig.uploader.received.map { it.keyVersion },
        )
        assertEquals("android_sync: the confirmed retry must empty the queue", 0, rig.service.pendingCount())
    }

    /** A failure here means an entry whose confirmation was lost is sent as a new record, or is lost. */
    @Test
    fun `a retry after a lost confirmation sends the same pair again`() {
        val rig = ServiceRig()
        rig.queue(clientId(1))
        val cause = IllegalStateException("the disk is gone")
        // The row is deleted first and the failure comes after: only the rollback can bring it back.
        rig.database.failNextRemove(cause, afterWriting = true)

        val thrown = thrownBy { rig.push() }

        assertSame("android_sync: the failed removal must leave pushPending unchanged", cause, thrown)
        assertEquals("android_sync: the entry must still be queued", listOf(clientId(1)), rig.database.queuedIds())

        val retry = rig.push()

        assertEquals("android_sync: the retry must push the entry", SyncReport(1, 0), retry)
        assertEquals("android_sync: the entry must be sent twice", listOf(clientId(1), clientId(1)), rig.uploadedIds())
        assertEquals(
            "android_sync: both sends must carry key version 0",
            listOf(0, 0),
            rig.uploader.received.map { it.keyVersion },
        )
        assertEquals("android_sync: the retry must empty the queue", 0, rig.service.pendingCount())
    }
}
