package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The delete branch of pushPending: a 429 keeps its Retry-After on the FAILED report,
 * other non-permanent refusals carry none, and a permanent refusal still parks. */
class QueuedSyncServiceDeleteRetryAfterTest {

    /** A delete answered 429 carries its Retry-After seconds on the FAILED report and
     * parks nothing; the row stays queued. */
    @Test
    fun `a delete answered 429 carries its Retry-After on the FAILED report`() {
        val rig = ServiceRig()
        rig.service.enqueueDelete(clientId(61))
        rig.uploader.thenThrowDelete(PermanentRefusalException(429, "rate limited", retryAfterSeconds = 90))

        val report = rig.push()

        assertEquals("android_sync: delete 429 stops with FAILED and keeps the wait", SyncReport(0, 1, 0, 90), report)
        assertEquals("android_sync: delete 429: the delete was attempted once", listOf(clientId(61)), rig.uploader.deleted)
        assertTrue("android_sync: delete 429: nothing was uploaded", rig.uploader.received.isEmpty())
        assertEquals("android_sync: delete 429: nothing is parked", 0, rig.service.parkedCount())
        assertNull("android_sync: delete 429: no parked reason", rig.database.parkedReason(clientId(61)))
        assertEquals("android_sync: delete 429: the row stays queued", listOf(clientId(61)), rig.database.queuedIds())
    }

    /** A delete answered 401, 409 or 503 stops the drain with FAILED and carries no wait. */
    @Test
    fun `a delete answered 401 409 or 503 carries no Retry-After`() {
        for (status in listOf(401, 409, 503)) {
            val rig = ServiceRig()
            rig.service.enqueueDelete(clientId(61))
            rig.uploader.thenThrowDelete(PermanentRefusalException(status, "refused $status"))

            val report = rig.push()

            assertEquals("android_sync: delete status $status stops with FAILED and carries no wait", SyncReport(0, 1, 0, null), report)
            assertNull("android_sync: delete status $status: no wait on the report", report.retryAfterSeconds)
            assertEquals("android_sync: delete status $status: the delete was attempted once", listOf(clientId(61)), rig.uploader.deleted)
            assertTrue("android_sync: delete status $status: nothing was uploaded", rig.uploader.received.isEmpty())
            assertEquals("android_sync: delete status $status: nothing is parked", 0, rig.service.parkedCount())
            assertEquals("android_sync: delete status $status: the row stays queued", listOf(clientId(61)), rig.database.queuedIds())
        }
    }

    /** A delete answered 401, 409 or 503 that wrongly carries a Retry-After must still report
     * no wait: only 429 carries a number (AGENTS.md:59). This kills the quiet mutant Q1 that copies
     * retryAfterSeconds on every non-permanent refusal: under Q1 the stray 90 is copied and the
     * report becomes SyncReport(0, 1, 0, 90), so the null assertion below goes RED. */
    @Test
    fun `a delete answered 401 409 or 503 with a stray Retry-After still carries no wait`() {
        for (status in listOf(401, 409, 503)) {
            val rig = ServiceRig()
            rig.service.enqueueDelete(clientId(61))
            rig.uploader.thenThrowDelete(PermanentRefusalException(status, "refused $status", retryAfterSeconds = 90))

            val report = rig.push()

            assertEquals("android_sync: delete status $status with a stray wait still reports no wait", SyncReport(0, 1, 0, null), report)
            assertNull("android_sync: delete status $status: the stray wait is not copied onto the report", report.retryAfterSeconds)
            assertEquals("android_sync: delete status $status: the delete was attempted once", listOf(clientId(61)), rig.uploader.deleted)
            assertEquals("android_sync: delete status $status: the row stays queued", listOf(clientId(61)), rig.database.queuedIds())
        }
    }

    /** A permanent refusal on a delete still parks the row and carries no wait (unchanged). */
    @Test
    fun `a permanent refusal on a delete still parks and carries no wait`() {
        val rig = ServiceRig()
        rig.service.enqueueDelete(clientId(61))
        rig.uploader.thenThrowDelete(PermanentRefusalException(400, "bad body"))

        val report = rig.push()

        assertEquals("android_sync: delete 400 is parked and carries no wait", SyncReport(0, 0, 1, null), report)
        assertEquals("android_sync: delete 400: one row parked", 1, rig.service.parkedCount())
        assertEquals("android_sync: delete 400: the parked row stays", listOf(clientId(61)), rig.database.queuedIds())
        assertEquals("android_sync: delete 400: the parked reason is kept", "bad body", rig.database.parkedReason(clientId(61)))
        assertNull("android_sync: delete 400: no wait on the report", report.retryAfterSeconds)
    }
}
