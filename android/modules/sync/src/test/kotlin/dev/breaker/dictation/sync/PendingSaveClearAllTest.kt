package dev.breaker.dictation.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Sign-out clear must clear pending_save markers as well as the queue. */
class PendingSaveClearAllTest {

    private val sqlite = DesktopSqlite()
    private val db = JdbcSyncQueueDatabase(sqlite)

    @After
    fun closeDatabase() = sqlite.close()

    @Test
    fun `clearAll clears the queue and the pending_save markers`() {
        db.insertIfAbsent(clientId(1), "text", "local", "small.en", 1_200L, 100L)
        db.markPendingSave(clientId(1), 7L)
        db.markPendingSave(clientId(2), 8L)
        assertEquals("android_sync: one row queued", 1, db.count())
        assertEquals("android_sync: two markers present", 2, db.pendingSaveIds(1000, null).size)

        db.clearAll()

        assertEquals("android_sync: the queue is empty after clearAll", 0, db.count())
        assertEquals("android_sync: pending_save is empty after clearAll", 0, db.pendingSaveIds(1000, null).size)
        assertTrue("android_sync: no marker survives sign-out", db.pendingSaveIds(1000, null).isEmpty())
    }

    /** The shared list both implementations iterate must contain the queue and pending_save deletes,
     * in order. Kills the "CLEAR_ALL_STATEMENTS drops DELETE_ALL_PENDING_SAVE" mutant by name. */
    @Test
    fun `the shared clear list contains the queue and pending_save deletes in order`() {
        assertEquals(
            "android_sync: CLEAR_ALL_STATEMENTS must clear the queue then the pending_save markers",
            listOf(SyncQueueSql.DELETE_ALL_QUEUE, SyncQueueSql.DELETE_ALL_PENDING_SAVE),
            SyncQueueSql.CLEAR_ALL_STATEMENTS,
        )
    }
}
