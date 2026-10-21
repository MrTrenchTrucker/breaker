package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the drain does with a row it cannot push. Such a row has a stored source that is not
 * `local` or `server`, a client id that is not a lowercase canonical UUID, or a body the codec
 * refuses. The drain skips it: the row stays queued, the walk goes on to the next row, and each
 * drain that reaches the row counts it in `failed`.
 *
 * A row that blocks the queue stops every transcription behind it from ever leaving the phone; a
 * row that is removed without a count tells the user nothing about what was lost.
 */
class QueuedSyncServiceSkipTest {

    private val rig = ServiceRig()

    private val upperCaseId = "00000000-0000-4000-8000-00000000ABCD"

    /** Stores a raw row with [source] through the storage seam, as the service would see it. */
    private fun insertRaw(id: String, source: String) {
        rig.database.insertIfAbsent(id, "quartz marmalade", source, "small.en", 1_200L, 1_700_000_000_000L)
    }

    /** A failure here means a row with a source that is not a wire word is sent, or it is not counted as skipped. */
    @Test
    fun `a stored source that is not local or server is skipped`() {
        insertRaw(clientId(1), "BOGUS")

        val report = rig.push()

        assertEquals("android_sync: a bad source must give SyncReport(0, 1)", SyncReport(0, 1), report)
        rig.assertNothingUploadedOrRemoved("bad source")
        assertEquals("android_sync: the bad row must stay queued", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A failure here means a client id that is not a lowercase canonical UUID is sent, or it is not counted as skipped. */
    @Test
    fun `a client id that is not a lowercase canonical UUID is skipped`() {
        insertRaw(upperCaseId, "local")

        val report = rig.push()

        assertEquals("android_sync: an uppercase id must give SyncReport(0, 1)", SyncReport(0, 1), report)
        assertEquals("android_sync: nothing may be uploaded for an uppercase id", emptyList<String>(), rig.uploadedIds())
        assertEquals("android_sync: the row must stay queued", listOf(upperCaseId), rig.database.queuedIds())
    }

    /** A failure here means a body the codec refuses is sent, or it is not counted as skipped. */
    @Test
    fun `a body the codec refuses is skipped`() {
        rig.service.enqueue(aTranscription(clientId(1), text = "lone \uD800 surrogate"))

        val report = rig.push()

        assertEquals("android_sync: a refused body must give SyncReport(0, 1)", SyncReport(0, 1), report)
        assertEquals("android_sync: nothing may be uploaded for a refused body", emptyList<String>(), rig.uploadedIds())
        assertEquals("android_sync: the refused row must stay queued", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A failure here means a skipped head stops the drain, so the row behind it waits. */
    @Test
    fun `a skipped entry does not block the entry behind it`() {
        insertRaw(clientId(1), "BOGUS")
        rig.queue(clientId(2))

        val report = rig.push()

        assertEquals("android_sync: one skipped and one pushed must give SyncReport(1, 1)", SyncReport(1, 1), report)
        assertEquals("android_sync: the entry behind the skipped one must be uploaded", listOf(clientId(2)), rig.uploadedIds())
        assertEquals("android_sync: only the skipped row may remain", listOf(clientId(1)), rig.database.queuedIds())
    }

    /** A failure here means a skipped row is removed from the queue, so its data is lost without a trace. */
    @Test
    fun `a skipped entry stays queued after the drain`() {
        insertRaw(clientId(1), "BOGUS")

        val report = rig.push()

        assertEquals("android_sync: the skipped row must be counted once", SyncReport(0, 1), report)
        assertEquals("android_sync: the skipped row must still be queued", listOf(clientId(1)), rig.database.queuedIds())
        assertEquals("android_sync: the skipped row must still count as pending", 1, rig.service.pendingCount())
    }

    /** A failure here means a skipped row is counted only once, or not on every drain that reaches it. */
    @Test
    fun `skipped entries count in failed on every drain`() {
        insertRaw(clientId(1), "BOGUS")
        insertRaw(upperCaseId, "local")

        val first = rig.push()
        val second = rig.push()

        assertEquals("android_sync: two skipped rows must give failed 2 on the first drain", SyncReport(0, 2), first)
        assertEquals("android_sync: two skipped rows must give failed 2 on the second drain", SyncReport(0, 2), second)
        assertEquals(
            "android_sync: both skipped rows must stay queued in order",
            listOf(clientId(1), upperCaseId),
            rig.database.queuedIds(),
        )
        assertEquals("android_sync: nothing may be uploaded", emptyList<String>(), rig.uploadedIds())
    }

    /** A failure here means a skipped row changes the order of the rows around it. */
    @Test
    fun `the order of the other entries survives a skipped entry`() {
        rig.queue(clientId(2))
        insertRaw(clientId(3), "BOGUS")
        rig.queue(clientId(4))

        val report = rig.push()

        assertEquals("android_sync: two pushed and one skipped must give SyncReport(2, 1)", SyncReport(2, 1), report)
        assertEquals(
            "android_sync: the uploads must stay in insert order with the skipped row between them",
            listOf(clientId(2), clientId(4)),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: only the skipped row may remain", listOf(clientId(3)), rig.database.queuedIds())
    }

    /** A failure here means a stop on FAILED does not add one to the skipped rows already counted. */
    @Test
    fun `a failed stop adds one to the skipped entries`() {
        insertRaw(clientId(1), "BOGUS")
        rig.queue(clientId(2))
        rig.uploader.script(UploadOutcome.FAILED)

        val report = rig.push()

        assertEquals("android_sync: one skipped then FAILED must give SyncReport(0, 2)", SyncReport(0, 2), report)
        assertEquals("android_sync: the drain must stop at the failed row", listOf(clientId(2)), rig.uploadedIds())
        assertEquals(
            "android_sync: the skipped row and the failed row must both stay queued",
            listOf(clientId(1), clientId(2)),
            rig.database.queuedIds(),
        )
    }

    /** A failure here means a stop on UNREACHABLE does not count the rows skipped before it. */
    @Test
    fun `a skipped entry is counted when the drain stops on UNREACHABLE`() {
        insertRaw(clientId(1), "BOGUS")
        rig.queue(clientId(2))
        rig.uploader.script(UploadOutcome.UNREACHABLE)

        val report = rig.push()

        assertEquals("android_sync: one skipped then UNREACHABLE must give SyncReport(0, 1)", SyncReport(0, 1), report)
        assertEquals("android_sync: the drain must stop at the unreachable row", listOf(clientId(2)), rig.uploadedIds())
        assertEquals(
            "android_sync: the skipped row and the unreachable row must both stay queued",
            listOf(clientId(1), clientId(2)),
            rig.database.queuedIds(),
        )
    }
}
