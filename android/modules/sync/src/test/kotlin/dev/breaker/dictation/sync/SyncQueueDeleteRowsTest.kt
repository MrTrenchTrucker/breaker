package dev.breaker.dictation.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delete rows of the queue, run on real SQLite through the twin.
 *
 * The service tests use an in-memory double, so the `kind` column and the park statement are never
 * executed there. These tests run the delete row's statements on a real SQLite engine
 * ([DesktopSqlite]) through [JdbcSyncQueueDatabase], so a delete enqueued reads back as a delete,
 * `isDeleteQueued` tells a delete from a save, and a delete row refused permanently parks.
 */
class SyncQueueDeleteRowsTest {

    private val sqlite = DesktopSqlite()
    private val db = JdbcSyncQueueDatabase(sqlite)

    @After
    fun closeDatabase() = sqlite.close()

    private fun clientId(n: Int) = "client-$n"

    /** SYNC_R2_B5: a failure here means isDeleteQueued answers true for a save or false for a delete. */
    @Test
    fun `isDeleteQueued is true only for a delete row`() {
        db.insertDeleteIfAbsent(clientId(1))
        db.insertIfAbsent(clientId(2), "quartz", "local", "small.en", 1_200L, 100L)

        assertTrue("android_sync: a queued delete is a queued delete", db.isDeleteQueued(clientId(1)))
        assertFalse("android_sync: a queued save is not a queued delete", db.isDeleteQueued(clientId(2)))
        assertFalse("android_sync: an unknown id is not a queued delete", db.isDeleteQueued(clientId(3)))
    }

    /** SYNC_R3: a failure here means a delete row refused permanently is not parked, so it would be retried. */
    @Test
    fun `a delete row refused permanently parks`() {
        db.insertDeleteIfAbsent(clientId(1))

        assertTrue("android_sync: parking a delete row reports a row parked", db.parkRow(clientId(1), "refused"))
        assertEquals("android_sync: the delete row carries the parked reason", "refused", db.parkedReason(clientId(1)))
        assertEquals("android_sync: the parked delete row is no longer pending", 0, db.count())
    }
}
