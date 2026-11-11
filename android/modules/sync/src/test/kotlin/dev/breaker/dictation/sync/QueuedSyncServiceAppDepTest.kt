package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** APP-DEP 1 and APP-DEP 2: the two public methods the app layer wires (the B5 backfill and the B7 sign-out clear). */
class QueuedSyncServiceAppDepTest {

    private val rig = ServiceRig()

    /** APP-DEP 1: isDeleteQueued is true for a queued delete, false for a queued save, false for an unknown id. */
    @Test
    fun `isDeleteQueued tells a queued delete from a save and an unknown id`() {
        rig.service.enqueueDelete(clientId(1))
        assertTrue("android_sync: isDeleteQueued is true for a queued delete", rig.service.isDeleteQueued(clientId(1)))
        rig.service.enqueue(aTranscription(clientId(2)))
        assertFalse("android_sync: isDeleteQueued is false for a queued save", rig.service.isDeleteQueued(clientId(2)))
        assertFalse("android_sync: isDeleteQueued is false for an id that is not queued", rig.service.isDeleteQueued(clientId(3)))
    }

    /** APP-DEP 2: clearPullCursor clears the stored cursor and never touches the queue. */
    @Test
    fun `clearPullCursor clears the cursor and leaves the queue untouched`() {
        rig.service.enqueue(aTranscription(clientId(1)))
        rig.database.savePullCursor("the-cursor")
        assertEquals("android_sync: the cursor is stored", "the-cursor", rig.database.pullCursor())
        assertEquals("android_sync: the queue holds its row", listOf(clientId(1)), rig.database.queuedIds())

        rig.service.clearPullCursor()

        assertNull("android_sync: clearPullCursor removes the cursor", rig.database.pullCursor())
        assertEquals("android_sync: the queue is untouched", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** APP-DEP 1 extended (SYNC_R2_B5): isDeleteQueued is true only for a delete row;
     * a queued save and a parked save answer false. */
    @Test
    fun `isDeleteQueued is false for a queued save and for a parked save`() {
        rig.service.enqueueDelete(clientId(1))
        assertTrue("android_sync: isDeleteQueued is true for a queued delete", rig.service.isDeleteQueued(clientId(1)))
        rig.service.enqueue(aTranscription(clientId(2)))
        assertFalse("android_sync: isDeleteQueued is false for a queued save", rig.service.isDeleteQueued(clientId(2)))
        rig.service.enqueue(aTranscription(clientId(3)))
        rig.database.parkRow(clientId(3), "refused")
        assertFalse("android_sync: isDeleteQueued is false for a parked save", rig.service.isDeleteQueued(clientId(3)))
        assertFalse("android_sync: isDeleteQueued is false for an unknown id", rig.service.isDeleteQueued(clientId(99)))
    }

    /** APP-DEP 2 extended (SYNC_27): clearAll really empties the queue, parked rows and live rows. */
    @Test
    fun `clearAll empties the queue, parked rows and live rows`() {
        rig.service.enqueue(aTranscription(clientId(71)))
        rig.service.enqueueDelete(clientId(72))
        rig.database.parkRow(clientId(71), "refused")

        assertEquals("android_sync: two rows are queued before clearAll", listOf(clientId(71), clientId(72)), rig.database.queuedIds())
        assertEquals("android_sync: one row is parked before clearAll", 1, rig.service.parkedCount())

        rig.service.clearAll()

        assertTrue("android_sync: clearAll leaves no rows queued", rig.database.queuedIds().isEmpty())
        assertEquals("android_sync: clearAll leaves no rows parked", 0, rig.service.parkedCount())
        assertEquals("android_sync: clearAll leaves nothing pending", 0, rig.service.pendingCount())
    }
}
