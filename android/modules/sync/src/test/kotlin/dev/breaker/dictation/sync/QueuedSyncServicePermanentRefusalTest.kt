package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permanent refusal: a 400 or 413 answer parks the row, keeps its reason,
 * counts it in this drain's parked count, and the queue moves on to the next row.
 * Any other status (401, 403, 409, 429, 503) or a network error stays transient.
 */
class QueuedSyncServicePermanentRefusalTest {

    private val rig = ServiceRig()

    /** A 400 parks the row, the drain moves on to the next row, and parked is counted. */
    @Test
    fun `a 400 permanent refusal parks the row and the queue moves on`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, "bad body"))

        val report = rig.push()

        assertEquals("android_sync: 400 parks one row and pushes the next", SyncReport(1, 0, 1), report)
        assertEquals("android_sync: the refused row was tried once, then the next row", listOf(clientId(1), clientId(2)), rig.uploadedIds())
        assertEquals("android_sync: the parked row stays in the store", listOf(clientId(1)), rig.database.queuedIds())
        assertEquals("android_sync: the parked row's reason is stored", "bad body", rig.database.parkedReason(clientId(1)))
        assertEquals("android_sync: one row is parked", 1, rig.database.parkedCount())
    }

    /** A 413 parks the row and the queue moves on. */
    @Test
    fun `a 413 permanent refusal parks the row and the queue moves on`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_413, "body too large"))

        val report = rig.push()

        assertEquals("android_sync: 413 parks one row and pushes the next", SyncReport(1, 0, 1), report)
        assertEquals("android_sync: the refused row was tried once, then the next row", listOf(clientId(1), clientId(2)), rig.uploadedIds())
        assertEquals("android_sync: the parked row stays in the store", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A parked row is never retried on the next drain. */
    @Test
    fun `a parked row is not retried on the next drain`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, "bad body"))
        rig.push()

        val second = rig.push()

        assertEquals("android_sync: the second drain has no live row and parks nothing", SyncReport(0, 0, 0), second)
        assertEquals("android_sync: the parked row was not uploaded again", listOf(clientId(1), clientId(2)), rig.uploadedIds())
        assertEquals("android_sync: the parked row is still in the store", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A 401 is not permanent: the row stays queued and the drain stops. */
    @Test
    fun `a 401 is not permanent and keeps the row queued`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(401, "no token"))

        val report = rig.push()

        assertEquals("android_sync: 401 is transient: one failure, no park", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: the refused row was tried once and the drain stopped", listOf(clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: both rows stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
        assertEquals("android_sync: no row is parked", 0, rig.database.parkedCount())
    }

    /** A 403 is not permanent. */
    @Test
    fun `a 403 is not permanent and keeps the row queued`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(403, "agent token"))

        val report = rig.push()

        assertEquals("android_sync: 403 is transient: one failure, no park", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: the refused row was tried once and the drain stopped", listOf(clientId(1)), rig.uploadedIds())
        assertEquals("android_sync: both rows stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
    }

    /** A 409 keeps today's behaviour: not permanent, drain stops. */
    @Test
    fun `a 409 keeps today's behaviour and is not permanent`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(409, "key version conflict"))

        val report = rig.push()

        assertEquals("android_sync: 409 is transient: one failure, no park", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: both rows stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
    }

    /** A 503 is not permanent. */
    @Test
    fun `a 503 is not permanent and keeps the row queued`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(503, "storage failure"))

        val report = rig.push()

        assertEquals("android_sync: 503 is transient: one failure, no park", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: both rows stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
    }

    /** A network error is not permanent. */
    @Test
    fun `a network error is not permanent and keeps the row queued`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(IllegalStateException("the network is down"))

        val report = rig.push()

        assertEquals("android_sync: a network error is transient: one failure, no park", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: both rows stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
        assertEquals("android_sync: no row is parked", 0, rig.database.parkedCount())
    }

    /** A 429 is not permanent. */
    @Test
    fun `a 429 is not permanent and keeps the row queued`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(429, "rate limited", retryAfterSeconds = 45))

        val report = rig.push()

        assertEquals(
            "android_sync: 429 is transient: one failure, no park, the number rides the report",
            SyncReport(0, 1, 0, 45),
            report,
        )
        assertEquals("android_sync: both rows stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
        assertEquals("android_sync: no row is parked", 0, rig.database.parkedCount())
    }

    /** A 429 whose Retry-After was no number carries none, and the rows stay queued. */
    @Test
    fun `a 429 without a number carries none and the rows stay queued`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(429, "rate limited"))

        val report = rig.push()

        assertEquals("android_sync: one failure and no number", SyncReport(0, 1, 0, null), report)
        assertNull("android_sync: no wait asked", report.retryAfterSeconds)
        assertEquals("android_sync: both rows stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
    }

    /** Only a 429 carries a number: a 503 whose exception holds one still reports none. */
    @Test
    fun `a 503 carries no retryAfterSeconds on the report`() {
        rig.queue(clientId(1))
        rig.uploader.thenThrow(PermanentRefusalException(503, "storage failure", retryAfterSeconds = 45))

        val report = rig.push()

        assertEquals("android_sync: the drain stops with one failure", 1, report.failed)
        assertNull("android_sync: only a 429 carries a number", report.retryAfterSeconds)
    }

    /** A PermanentRefusalException carries retryAfterSeconds. */
    @Test
    fun `a PermanentRefusalException carries retryAfterSeconds`() {
        val ex = PermanentRefusalException(429, "rate limited", retryAfterSeconds = 45)
        assertEquals(429, ex.status)
        assertEquals(45, ex.retryAfterSeconds)
        assertEquals("rate limited", ex.reason)
    }

    /** A PermanentRefusalException built without retryAfterSeconds defaults to null. */
    @Test
    fun `a PermanentRefusalException without retryAfterSeconds defaults to null`() {
        val ex = PermanentRefusalException(400, "bad body")
        assertEquals(400, ex.status)
        assertNull(ex.retryAfterSeconds)
        assertEquals("bad body", ex.reason)
    }

    /** The reason is stored truncated to the cap (200 chars), never carrying row text. */
    @Test
    fun `the reason is capped at 200 characters`() {
        val longReason = "x".repeat(500)
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, longReason))

        val report = rig.push()

        assertEquals("android_sync: 400 parks one row and pushes the next", SyncReport(1, 0, 1), report)
        val stored = rig.database.parkedReason(clientId(1))
        assertEquals("android_sync: the stored reason is truncated to the cap", 200, stored?.length)
        assertTrue("android_sync: the stored reason is a prefix of the server's reason", longReason.startsWith(stored!!))
    }

    /** A parked row stays parked after a second drain. */
    @Test
    fun `a parked row stays parked after a second drain`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, "bad body"))
        rig.push()
        rig.push()

        assertEquals("android_sync: the row is still parked after two drains", 1, rig.database.parkedCount())
        assertEquals("android_sync: the parked row is still queued", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A thrown non-refusal exception parks nothing and counts as FAILED. */
    @Test
    fun `a non-refusal exception parks nothing and counts as failed`() {
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(IllegalStateException("the disk is gone"))

        val report = rig.push()

        assertEquals("android_sync: a non-refusal exception counts as FAILED", SyncReport(0, 1, 0), report)
        assertEquals("android_sync: no row is parked", 0, rig.database.parkedCount())
        assertEquals("android_sync: both rows stay queued", listOf(clientId(1), clientId(2)), rig.database.queuedIds())
    }

    /** The reason never carries row text even when the exception's message or cause holds it. */
    @Test
    fun `the reason never carries row text from the exception message or cause`() {
        val rowText = "secret dictated text must not leak"
        val cause = IllegalStateException(rowText)
        rig.queue(clientId(1), clientId(2))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, "bad body", cause))

        val report = rig.push()

        assertEquals("android_sync: 400 parks one row and pushes the next", SyncReport(1, 0, 1), report)
        val stored = rig.database.parkedReason(clientId(1))
        assertEquals("android_sync: the reason equals the capped reason argument", "bad body", stored)
        assertTrue("android_sync: parkedReason does not contain the row text", !stored!!.contains(rowText))
    }

    /** A row parked earlier in the drain is still counted when a later row ends the drain on FAILED. */
    @Test
    fun `a parked row is still counted when a later row ends the drain on a failure`() {
        rig.queue(clientId(1), clientId(2), clientId(3))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, "bad body"))
        rig.uploader.script(UploadOutcome.FAILED)

        val report = rig.push()

        assertEquals("android_sync: one row parked, then one failure", SyncReport(0, 1, 1), report)
        assertEquals("android_sync: the parked row is parked", 1, rig.database.parkedCount())
    }

    /** A row parked earlier in the drain is still counted when a later row ends the drain as unreachable. */
    @Test
    fun `a parked row is still counted when a later row ends the drain as unreachable`() {
        rig.queue(clientId(1), clientId(2), clientId(3))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, "bad body"))
        rig.uploader.script(UploadOutcome.UNREACHABLE)

        val report = rig.push()

        assertEquals("android_sync: one row parked, then the drain stopped quietly", SyncReport(0, 0, 1), report)
    }

    /** B6: a delete answered 400 parks like a save; the drain goes on and a later save still pushes. */
    @Test
    fun `a delete answered 400 is parked and the next row still drains`() {
        rig.uploader.thenThrowDelete(PermanentRefusalException(400, "bad_request"))
        rig.uploader.script(UploadOutcome.CONFIRMED)
        rig.service.enqueueDelete(clientId(8))
        rig.service.enqueue(aTranscription(clientId(9), text = "the later save"))

        val report = rig.push()

        assertEquals("android_sync: the delete parks and the save confirms", SyncReport(1, 0, 1), report)
        assertEquals("android_sync: the delete was attempted once", listOf(clientId(8)), rig.uploader.deleted)
        assertEquals("android_sync: the later save was uploaded", listOf(clientId(9)), rig.uploadedIds())
        assertEquals("android_sync: the parked delete stays in the store", listOf(clientId(8)), rig.database.queuedIds())
        assertEquals("android_sync: the parked reason is kept", "bad_request", rig.database.parkedReason(clientId(8)))
        assertEquals("android_sync: one row is parked", 1, rig.database.parkedCount())

        rig.uploader.script(UploadOutcome.CONFIRMED)
        rig.service.enqueue(aTranscription(clientId(7), text = "another save"))
        val second = rig.push()
        assertEquals("android_sync: the drain did not stop; a later save pushes", SyncReport(1, 0, 0), second)
        assertEquals("android_sync: only the parked delete remains", listOf(clientId(8)), rig.database.queuedIds())
        assertEquals("android_sync: no live rows left", 0, rig.service.pendingCount())
    }
}
