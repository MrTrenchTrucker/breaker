package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parked row in the store, on both doubles: park, read the reason, parked row skipped by
 * head/nextAfter/tailSeq/count, reason survives a restart (SyncQueueRestartTest idiom), remove
 * deletes a parked row.
 */
class ParkedRowStoreTest {

    private fun newRig() = ServiceRig()

    /** A parked row is skipped by head and nextAfter on the in-memory double. */
    @Test
    fun `a parked row is skipped by head and nextAfter on the in-memory double`() {
        val rig = newRig()
        rig.queue(clientId(1), clientId(2), clientId(3))
        rig.database.parkRow(clientId(2), "bad body")

        val head = rig.database.head()
        assertEquals("android_sync: head skips the parked row", clientId(1), head?.clientId)
        val next = rig.database.nextAfter(head!!.seq)
        assertEquals("android_sync: nextAfter skips the parked row", clientId(3), next?.clientId)
        assertEquals("android_sync: count excludes the parked row", 2, rig.database.count())
        assertEquals("android_sync: parkedCount counts the parked row", 1, rig.database.parkedCount())
        assertEquals("android_sync: parkedReason returns the reason", "bad body", rig.database.parkedReason(clientId(2)))
        assertNull("android_sync: parkedReason is null for a live row", rig.database.parkedReason(clientId(1)))
    }

    /** A parked row is skipped by head and nextAfter on the JDBC twin. */
    @Test
    fun `a parked row is skipped by head and nextAfter on the JDBC twin`() {
        val sqlite = DesktopSqlite()
        val db = JdbcSyncQueueDatabase(sqlite)
        try {
            db.insertIfAbsent(clientId(1), "text 1", "local", "small.en", 1_200L, 100L)
            db.insertIfAbsent(clientId(2), "text 2", "local", "small.en", 1_200L, 200L)
            db.insertIfAbsent(clientId(3), "text 3", "local", "small.en", 1_200L, 300L)
            db.parkRow(clientId(2), "bad body")

            val head = db.head()
            assertEquals("android_sync: head skips the parked row", clientId(1), head?.clientId)
            val next = db.nextAfter(head!!.seq)
            assertEquals("android_sync: nextAfter skips the parked row", clientId(3), next?.clientId)
            assertEquals("android_sync: count excludes the parked row", 2, db.count())
            assertEquals("android_sync: parkedCount counts the parked row", 1, db.parkedCount())
            assertEquals("android_sync: parkedReason returns the reason", "bad body", db.parkedReason(clientId(2)))
        } finally {
            sqlite.close()
        }
    }

    /** A parked row's reason survives a restart (close and reopen the file). */
    @Test
    fun `a parked row's reason survives a restart`() {
        val file = java.io.File.createTempFile("android-sync-parked", ".db")
        try {
            val sqlite1 = DesktopSqlite(file)
            val db1 = JdbcSyncQueueDatabase(sqlite1)
            db1.insertIfAbsent(clientId(1), "text 1", "local", "small.en", 1_200L, 100L)
            db1.parkRow(clientId(1), "bad body")
            db1.close()
            sqlite1.close()

            val sqlite2 = DesktopSqlite(file)
            val db2 = JdbcSyncQueueDatabase(sqlite2)
            try {
                assertNull("android_sync: head skips the only row, which is parked", db2.head())
                assertEquals("android_sync: the reason survives reopen", "bad body", db2.parkedReason(clientId(1)))
                assertEquals("android_sync: parkedCount survives reopen", 1, db2.parkedCount())
                assertEquals("android_sync: count excludes the parked row after reopen", 0, db2.count())
            } finally {
                db2.close()
                sqlite2.close()
            }
        } finally {
            file.delete()
        }
    }

    /** Remove deletes a parked row on both doubles. */
    @Test
    fun `remove deletes a parked row`() {
        val rig = newRig()
        rig.queue(clientId(1), clientId(2))
        rig.database.parkRow(clientId(1), "bad body")

        val removed = rig.database.remove(clientId(1))
        assertTrue("android_sync: remove deletes a parked row", removed)
        assertEquals("android_sync: the parked row is gone", listOf(clientId(2)), rig.database.queuedIds())
        assertEquals("android_sync: parkedCount is zero after removal", 0, rig.database.parkedCount())
        assertNull("android_sync: parkedReason is null after removal", rig.database.parkedReason(clientId(1)))
    }

    /** pendingCount excludes parked rows on the service. */
    @Test
    fun `pendingCount excludes parked rows on the service`() {
        val rig = newRig()
        rig.queue(clientId(1), clientId(2))
        rig.database.parkRow(clientId(1), "bad body")

        assertEquals("android_sync: pendingCount excludes the parked row", 1, rig.service.pendingCount())
        assertEquals("android_sync: parkedCount is one", 1, rig.service.parkedCount())
        assertEquals("android_sync: parkedReason delegates", "bad body", rig.service.parkedReason(clientId(1)))
    }

    /** The parked row's reason never contains the row's text. */
    @Test
    fun `the parked reason never contains the row text`() {
        val rig = newRig()
        rig.service.enqueue(aTranscription(clientId(1), text = "secret dictated text"))
        rig.uploader.thenThrow(PermanentRefusalException(PermanentRefusalException.PERMANENT_REFUSAL_400, "bad body"))
        rig.push()

        val reason = rig.database.parkedReason(clientId(1))
        assertEquals("android_sync: the reason is the server's word only", "bad body", reason)
        assertTrue("android_sync: the reason does not contain the row text", !reason!!.contains("secret dictated text"))
    }

    /** The real SQL delete removes a parked row too (the delete statement is not filtered). */
    @Test
    fun `remove deletes a parked row on the real SQL`() {
        val sqlite = DesktopSqlite()
        val db = JdbcSyncQueueDatabase(sqlite)
        try {
            db.insertIfAbsent(clientId(1), "text 1", "local", "small.en", 1_200L, 100L)
            db.insertIfAbsent(clientId(2), "text 2", "local", "small.en", 1_200L, 200L)
            db.parkRow(clientId(1), "bad body")

            assertTrue("android_sync: remove reports the parked row as removed", db.remove(clientId(1)))

            assertEquals("android_sync: no row is parked after the delete", 0, db.parkedCount())
            assertNull("android_sync: the parked reason is gone with the row", db.parkedReason(clientId(1)))
            assertEquals("android_sync: only the live row is left", listOf(clientId(2)), db.drainQueuedIds())
        } finally {
            sqlite.close()
        }
    }
}
