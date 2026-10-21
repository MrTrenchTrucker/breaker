package dev.breaker.dictation.sync

import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What survives a restart.
 *
 * The queue exists so that a dictation made without a connection is still there after the app is closed and
 * opened again. These tests keep the queue in a real file: each one opens [JdbcSyncQueueDatabase] on a
 * temporary file the test creates, writes, closes, opens the same file again and looks. The file, and any
 * journal beside it, is deleted after every test.
 *
 * **The limit.** The file is a desktop SQLite file written through the test-only `sqlite-jdbc` jar, not the
 * phone's. What is proved is that the statements in [SyncQueueSql] keep their rows across a close and a
 * reopen.
 */
class SyncQueueRestartTest {

    private var file: File? = null
    private val opened = ArrayList<DesktopSqlite>()

    @Before
    fun createFile() {
        file = File.createTempFile("android-sync-queue", ".db")
    }

    @After
    fun closeAndDeleteFile() {
        try {
            opened.forEach { it.close() }
        } finally {
            val path = file?.path
            if (path != null) {
                for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                    File(path + suffix).delete()
                }
            }
        }
    }

    /** Opens the temporary file as a queue. Every handle opened here is closed after the test. */
    private fun open(): JdbcSyncQueueDatabase {
        val sqlite = DesktopSqlite(file)
        opened.add(sqlite)
        return JdbcSyncQueueDatabase(sqlite)
    }

    /** A failure here means entries are lost or reordered by a close and a reopen. */
    @Test
    fun `entries survive closing and reopening the database in order`() {
        val first = open()
        first.insertIfAbsent("kept-a", "text of a", "server", "large-v3", 4_500L, 300L)
        first.insertIfAbsent("kept-b", "quartz marmalade for kept-b", "local", "small.en", 1_200L, 100L)
        first.insertIfAbsent("kept-c", "quartz marmalade for kept-c", "local", "small.en", 1_200L, 200L)
        first.close()

        val second = open()
        assertEquals("android_sync: all three entries must be there after the reopen", 3, second.count())
        val head = second.head() ?: throw AssertionError("android_sync: the reopened queue has no head")
        assertEquals("android_sync: id of the head after the reopen", "kept-a", head.clientId)
        assertEquals("android_sync: text of the head after the reopen", "text of a", head.text)
        assertEquals("android_sync: source of the head after the reopen", "server", head.source)
        assertEquals("android_sync: model of the head after the reopen", "large-v3", head.model)
        assertEquals("android_sync: duration of the head after the reopen", 4_500L, head.durationMs)
        assertEquals("android_sync: creation time of the head after the reopen", 300L, head.createdAtMs)

        assertTrue(
            "android_sync: a new entry after the reopen must be queued",
            second.insertIfAbsent("kept-d", "quartz marmalade for kept-d", "local", "small.en", 1_200L, 50L),
        )
        assertEquals(
            "android_sync: the entries must drain in the order they were queued, across the reopen",
            listOf("kept-a", "kept-b", "kept-c", "kept-d"),
            second.drainQueuedIds(),
        )
    }

    /** A failure here means a drain on a reopened queue does not send what was queued before the close. */
    @Test
    fun `a drain after reopening sends them with the same client ids`() {
        val first = open()
        val firstService = QueuedSyncService(database = first, uploader = ScriptedUploader(), probe = ScriptedProbe(true))
        firstService.enqueue(aTranscription(clientId(1), source = TranscriptionSource.SERVER, createdAt = 900))
        firstService.enqueue(aTranscription(clientId(2), createdAt = 800))
        firstService.enqueue(aTranscription(clientId(3), createdAt = 700))
        first.close()

        val uploader = ScriptedUploader()
        val service = QueuedSyncService(database = open(), uploader = uploader, probe = ScriptedProbe(true))
        assertEquals("android_sync: the service must see the three stored entries", 3, service.pendingCount())

        val report = service.pushPending()

        assertEquals("android_sync: all three entries must be pushed", SyncReport(3, 0), report)
        assertEquals(
            "android_sync: the client ids sent, in order",
            listOf(clientId(1), clientId(2), clientId(3)),
            uploader.received.map { it.clientId },
        )
        assertEquals("android_sync: the key version sent", listOf(0, 0, 0), uploader.received.map { it.keyVersion })
        val bodies = uploader.received.map { SyncBodyCodec.decodePlain(it.encrypted) }
        assertEquals("android_sync: the creation times sent", listOf(900L, 800L, 700L), bodies.map { it.createdAtMs })
        assertEquals(
            "android_sync: the sources sent",
            listOf(SyncSource.SERVER, SyncSource.LOCAL, SyncSource.LOCAL),
            bodies.map { it.source },
        )
        assertEquals("android_sync: the queue must be empty after the drain", 0, service.pendingCount())
        service.close()

        val afterwards = open()
        assertEquals("android_sync: the confirmed removals must be in the file", 0, afterwards.count())
        assertNull(afterwards.head())
    }

    /** A failure here means a removal that was committed comes back after a restart, so an entry is sent twice. */
    @Test
    fun `a removal committed before the close stays removed after reopening`() {
        val first = open()
        first.insertIfAbsent("gone-a", "quartz marmalade for gone-a", "local", "small.en", 1_200L, 1_700_000_000_000L)
        first.insertIfAbsent("gone-b", "quartz marmalade for gone-b", "local", "small.en", 1_200L, 1_700_000_000_000L)
        first.insertIfAbsent("stays-c", "quartz marmalade for stays-c", "local", "small.en", 1_200L, 1_700_000_000_000L)
        first.insertIfAbsent("stays-d", "quartz marmalade for stays-d", "local", "small.en", 1_200L, 1_700_000_000_000L)

        val removedInTransaction = first.transaction { first.remove("gone-b") }
        val removedDirectly = first.remove("gone-a")
        assertTrue("android_sync: remove inside a transaction must find the row", removedInTransaction)
        assertTrue("android_sync: remove outside a transaction must find the row", removedDirectly)
        first.close()

        val second = open()
        assertEquals("android_sync: two entries must remain after the reopen", 2, second.count())
        assertEquals(
            "android_sync: only the entries that were not removed come back",
            listOf("stays-c", "stays-d"),
            second.drainQueuedIds(),
        )
    }

    /** A failure here means a removal that was rolled back is lost after a restart, so a dictation is never sent. */
    @Test
    fun `an entry whose removal was rolled back is still there after reopening`() {
        val first = open()
        first.insertIfAbsent("held-a", "quartz marmalade for held-a", "local", "small.en", 1_200L, 1_700_000_000_000L)
        first.insertIfAbsent("held-b", "quartz marmalade for held-b", "local", "small.en", 1_200L, 1_700_000_000_000L)

        val planned = IllegalStateException("android_sync: planned failure after the remove")
        val thrown = thrownBy {
            first.transaction<Unit> {
                first.remove("held-a")
                first.insertIfAbsent("never-kept", "quartz marmalade for never-kept", "local", "small.en", 1_200L, 1_700_000_000_000L)
                throw planned
            }
        }
        assertSame("android_sync: the transaction must rethrow the very exception the block threw", planned, thrown)
        assertEquals("android_sync: both entries must be back before the close", 2, first.count())
        first.close()

        val second = open()
        assertEquals("android_sync: both entries must be in the file after the reopen", 2, second.count())
        val head = second.head() ?: throw AssertionError("android_sync: the reopened queue has no head")
        assertEquals("android_sync: the entry whose removal was rolled back is still the head", "held-a", head.clientId)
        assertEquals(
            "android_sync: the rolled back insert must not be in the file",
            listOf("held-a", "held-b"),
            second.drainQueuedIds(),
        )
    }
}
