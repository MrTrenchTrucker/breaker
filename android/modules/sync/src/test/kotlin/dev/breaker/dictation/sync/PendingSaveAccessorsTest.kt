package dev.breaker.dictation.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The queue store's pending-save marker accessors, run on real SQLite through the JVM twin. */
class PendingSaveAccessorsTest {

    private val sqlite = DesktopSqlite()
    private val db = JdbcSyncQueueDatabase(sqlite)

    @After
    fun closeDatabase() = sqlite.close()

    /** A fresh store has no pending-save markers. */
    @Test
    fun `a fresh store holds no pending-save markers`() {
        assertEquals("android_sync: a fresh store has an empty pending_save", 0, db.pendingSaveIds(1000, null).size)
    }

    /** markPendingSave stamps a marker with the run token and reports it; a second call with the same id and token adds nothing. */
    @Test
    fun `markPendingSave is insert-if-absent and reports whether it added`() {
        assertTrue("android_sync: the first mark of an id must add a marker", db.markPendingSave(clientId(1), 7L))
        assertFalse("android_sync: a second mark of the same id and token must add nothing", db.markPendingSave(clientId(1), 7L))
        assertEquals("android_sync: one marker is present", 1, db.pendingSaveIds(1000, null).size)
        assertEquals("android_sync: the marker carries the marked id and run token", listOf(PendingSaveMarker(clientId(1), 7L)), db.pendingSaveIds(1000, null))
    }

    /** clearPendingSave removes a marker and reports it; clearing an absent id reports false. */
    @Test
    fun `clearPendingSave removes a marker and reports whether it removed`() {
        db.markPendingSave(clientId(1), 7L)
        assertTrue("android_sync: clearing a present marker must say it was removed", db.clearPendingSave(clientId(1)))
        assertFalse("android_sync: clearing an absent marker must report false", db.clearPendingSave(clientId(1)))
        assertEquals("android_sync: the marker is gone", 0, db.pendingSaveIds(1000, null).size)
    }

    /** pendingSaveIds pages: page 1 then the next page after the last id, each marker carrying its run token. */
    @Test
    fun `pendingSaveIds pages through the markers`() {
        db.markPendingSave(clientId(1), 7L)
        db.markPendingSave(clientId(2), 8L)
        db.markPendingSave(clientId(3), 9L)
        val page1 = db.pendingSaveIds(2, null)
        assertEquals("android_sync: page 1 returns at most limit markers", 2, page1.size)
        assertEquals("android_sync: page 1 markers carry their run tokens", listOf(PendingSaveMarker(clientId(1), 7L), PendingSaveMarker(clientId(2), 8L)), page1)
        val page2 = db.pendingSaveIds(2, page1.last().clientId)
        assertEquals("android_sync: the next page holds the remaining marker", 1, page2.size)
        assertEquals("android_sync: page 2 starts after the last id of page 1", clientId(3), page2[0].clientId)
        val page3 = db.pendingSaveIds(2, page2.last().clientId)
        assertTrue("android_sync: a page past the end is empty", page3.isEmpty())
    }
}
