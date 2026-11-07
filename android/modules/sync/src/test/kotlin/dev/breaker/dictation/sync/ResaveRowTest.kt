package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test F: the resave rule. A second enqueue of a parked id unparks and replaces it,
 * keeping its seq. A resave of a LIVE id is unchanged (first copy kept).
 */
class ResaveRowTest {

    private fun newRig() = ServiceRig()

    /** A second enqueue of a parked id unparks and replaces it, keeping its seq. */
    @Test
    fun `a second enqueue of a parked id unparks and replaces it keeping its seq`() {
        val rig = newRig()
        rig.queue(clientId(1))
        rig.database.parkRow(clientId(1), "bad body")

        assertTrue("android_sync: the row is parked before resave", rig.database.parkedReason(clientId(1)) != null)

        rig.service.enqueue(aTranscription(clientId(1), text = "replacement text"))

        assertEquals("android_sync: the parked row was unparked", null, rig.database.parkedReason(clientId(1)))
        assertEquals("android_sync: the row still exists", listOf(clientId(1)), rig.database.queuedIds())
        assertEquals("android_sync: count is still one", 1, rig.database.count())
        assertEquals("android_sync: the text was replaced", "replacement text", rig.database.head()!!.text)
    }

    /** A resave of a LIVE id is unchanged: first copy kept, as today. */
    @Test
    fun `a resave of a live id is unchanged first copy kept`() {
        val rig = newRig()
        rig.queue(clientId(1))

        rig.service.enqueue(aTranscription(clientId(1), text = "replacement text"))

        assertEquals("android_sync: first copy kept for a live id", listOf(clientId(1)), rig.database.queuedIds())
        assertEquals("android_sync: count is still one", 1, rig.database.count())
        assertEquals("android_sync: the text was not replaced", "quartz marmalade for " + clientId(1), rig.database.head()!!.text)
        assertNull("android_sync: no parked reason on a live row", rig.database.parkedReason(clientId(1)))
    }

    /** A resave keeps the seq so the row stays in drain order. */
    @Test
    fun `a resave keeps the seq so the row stays in drain order`() {
        val rig = newRig()
        rig.queue(clientId(1), clientId(2))
        val seqBefore = rig.database.head()!!.seq
        rig.database.parkRow(clientId(1), "bad body")

        rig.service.enqueue(aTranscription(clientId(1), text = "replacement text"))

        val seqAfter = rig.database.head()!!.seq
        assertEquals("android_sync: the seq is kept after resave", seqBefore, seqAfter)
    }

    /** A resave makes the row go through the normal drain again. */
    @Test
    fun `a resaved row goes through the normal drain again`() {
        val rig = newRig()
        rig.queue(clientId(1), clientId(2))
        rig.database.parkRow(clientId(1), "bad body")
        rig.push() // first drain: clientId(1) parked, clientId(2) confirmed

        rig.service.enqueue(aTranscription(clientId(1), text = "replacement text"))

        rig.uploader.script(UploadOutcome.CONFIRMED)
        val second = rig.push()

        assertEquals("android_sync: the second drain confirms the resaved row", SyncReport(1, 0, 0), second)
        assertEquals(
            "android_sync: the row left over from the first drain, then the resaved row",
            listOf(clientId(2), clientId(1)),
            rig.uploadedIds(),
        )
        assertEquals("android_sync: the resaved row was removed", emptyList<String>(), rig.database.queuedIds())
    }

    /** The store statement leaves a live row unchanged and says so, on the real-SQL twin. */
    @Test
    fun `resaveRow leaves a live row unchanged on the JDBC twin`() {
        val sqlite = DesktopSqlite()
        val db = JdbcSyncQueueDatabase(sqlite)
        try {
            db.insertIfAbsent(clientId(1), "first text", "local", "small.en", 1_200L, 100L)

            val changed = db.resaveRow(clientId(1), "other text", "local", "small.en", 1_200L, 200L)

            assertFalse("android_sync: a live row is not resaved", changed)
            assertEquals("android_sync: the live row keeps its first text", "first text", db.head()!!.text)
        } finally {
            sqlite.close()
        }
    }

    /** The real SQL clears the parked reason on a resave, and the next drain sends the row. */
    @Test
    fun `a resaved parked id on the real SQL is pending again and the next drain pushes it`() {
        val sqlite = DesktopSqlite()
        val db = JdbcSyncQueueDatabase(sqlite)
        try {
            db.insertIfAbsent(clientId(1), "first text", "local", "small.en", 1_200L, 100L)
            db.insertIfAbsent(clientId(2), "second text", "local", "small.en", 1_200L, 200L)
            assertTrue("android_sync: the row is parked", db.parkRow(clientId(1), "bad body"))

            assertTrue("android_sync: a parked row is resaved", db.resaveRow(clientId(1), "other text", "local", "small.en", 1_200L, 300L))

            assertNull("android_sync: the reason is cleared", db.parkedReason(clientId(1)))
            assertEquals("android_sync: nothing is parked any more", 0, db.parkedCount())
            assertEquals("android_sync: both rows are pending", 2, db.count())
            val uploader = ScriptedUploader()
            val service = QueuedSyncService(database = db, uploader = uploader, probe = ScriptedProbe(true))
            val report = service.pushPending()
            assertEquals("android_sync: the drain sends both rows", SyncReport(2, 0, 0), report)
            assertEquals(
                "android_sync: the resaved row keeps its place before the second row",
                listOf(clientId(1), clientId(2)),
                uploader.received.map { it.clientId },
            )
        } finally {
            sqlite.close()
        }
    }
}
