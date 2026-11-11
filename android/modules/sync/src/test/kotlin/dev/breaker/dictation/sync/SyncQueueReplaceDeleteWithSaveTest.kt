package dev.breaker.dictation.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNC_R2_B3 both directions: replaceQueuedDeleteWithSave touches only a delete row
 * (the row keeps its seq and becomes kind save with the new text/source/model/duration),
 * while a live save row is never matched and keeps its first copy. Runs on the real
 * DesktopSqlite/Jdbc twin, not on the in-memory double. */
class SyncQueueReplaceDeleteWithSaveTest {

    private var file: File? = null
    private val files = ArrayList<File>()

    private fun freshDb(): JdbcSyncQueueDatabase {
        val f = File.createTempFile("android-sync-b3-replace", ".db")
        files.add(f)
        val sqlite = DesktopSqlite(f)
        sqlite.createSchema()
        return JdbcSyncQueueDatabase(sqlite)
    }

    @Test
    fun `a queued delete is replaced by a save at the same seq`() {
        val db = freshDb()
        assertTrue("android_sync: the delete row was inserted", db.insertDeleteIfAbsent(clientId(11)))
        val seq = db.head()!!.seq
        assertTrue("android_sync: the delete row is replaced",
            db.replaceQueuedDeleteWithSave(clientId(11), "replacement text", "server", "large-v3", 3_300, 777))

        val entry = db.head()!!
        assertEquals("android_sync: the replacement row keeps its seq", seq, entry.seq)
        assertEquals("android_sync: the replacement row reads back kind save", SyncQueueSql.KIND_SAVE, entry.kind)
        assertEquals("android_sync: the replacement text", "replacement text", entry.text)
        assertEquals("android_sync: the replacement source", "server", entry.source)
        assertEquals("android_sync: the replacement model", "large-v3", entry.model)
        assertEquals("android_sync: the replacement duration", 3_300L, entry.durationMs)
        assertEquals("android_sync: still exactly one row", 1, db.queuedIds().size)
        assertEquals("android_sync: the replacement is still under the same id", listOf(clientId(11)), db.queuedIds())
    }

    @Test
    fun `a queued live save is not replaced and keeps its first copy and its seq`() {
        val db = freshDb()
        assertTrue("android_sync: the save row was inserted",
            db.insertIfAbsent(clientId(12), "first words about quartz", "local", "small.en", 1_200, 100))
        val seq = db.head()!!.seq
        assertFalse("android_sync: replaceQueuedDeleteWithSave must not match a save row",
            db.replaceQueuedDeleteWithSave(clientId(12), "replacement text", "server", "large-v3", 3_300, 777))

        val entry = db.head()!!
        assertEquals("android_sync: the first copy keeps its seq", seq, entry.seq)
        assertEquals("android_sync: the first copy keeps kind save", SyncQueueSql.KIND_SAVE, entry.kind)
        assertEquals("android_sync: the first copy keeps its text", "first words about quartz", entry.text)
        assertEquals("android_sync: the first copy keeps its source", "local", entry.source)
        assertEquals("android_sync: the first copy keeps its model", "small.en", entry.model)
        assertEquals("android_sync: still exactly one row", 1, db.queuedIds().size)
    }
}
