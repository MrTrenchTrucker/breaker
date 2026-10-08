package dev.breaker.server.whisper.jobs

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.db.TestDatabases
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Looks at the raw files (database and write-ahead log) while the connection stays open:
 * a closed connection checkpoints and hides what an open one leaves behind. Every erasure
 * check has a control that proves the search can see the bytes before they are erased.
 */
internal class AudioErasureTest : TempDatabaseTest() {

    private val clock = MutableClock(JobFixtures.START)
    private val resultBytes: ByteArray = JobFixtures.MARKER_RESULT.toByteArray(Charsets.US_ASCII)

    private fun audioFiles(temp: TempDatabase): List<String> =
        TestDatabases.filesHolding(temp, JobFixtures.MARKER_PROBE)

    private fun resultFiles(temp: TempDatabase): List<String> =
        TestDatabases.filesHolding(temp, resultBytes)

    private fun rowsWithAudio(temp: TempDatabase): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM jobs WHERE audio IS NOT NULL").single()
        }

    @Test
    fun `the search finds the audio of a queued job in the files`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)

        runBlocking { store.enqueue(1L, JobFixtures.MARKER_AUDIO) }

        assertFalse(
            "whisper-server: the search must see the audio of a queued job, or it proves nothing",
            audioFiles(temp).isEmpty(),
        )
    }

    @Test
    fun `the search finds the audio of a processing job in the files`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)

        runBlocking { JobFixtures.claimed(store, 1L, JobFixtures.MARKER_AUDIO) }

        assertFalse(
            "whisper-server: the search must see the audio of a processing job, or it proves nothing",
            audioFiles(temp).isEmpty(),
        )
    }

    @Test
    fun `completing a job erases its audio from the row and from every file`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val claim: ClaimedJob = runBlocking { JobFixtures.claimed(store, 1L, JobFixtures.MARKER_AUDIO) }
        assertFalse("whisper-server: control, the audio must be in a file before the job ends", audioFiles(temp).isEmpty())

        runBlocking { store.complete(claim.job.id, "a transcript") }

        assertEquals("whisper-server: no file may hold the audio of a done job", emptyList<String>(), audioFiles(temp))
        assertEquals("whisper-server: the audio column must be NULL", 0L, rowsWithAudio(temp))
    }

    @Test
    fun `failing a job erases its audio from the row and from every file`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val claim: ClaimedJob = runBlocking { JobFixtures.claimed(store, 1L, JobFixtures.MARKER_AUDIO) }
        assertFalse("whisper-server: control, the audio must be in a file before the job ends", audioFiles(temp).isEmpty())

        runBlocking { store.fail(claim.job.id, "the service said no") }

        assertEquals("whisper-server: no file may hold the audio of a failed job", emptyList<String>(), audioFiles(temp))
        assertEquals("whisper-server: the audio column must be NULL", 0L, rowsWithAudio(temp))
    }

    @Test
    fun `audio that already reached the database file is zeroed in it when the job ends`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val databaseName: String = temp.file.fileName.toString()
        val walName: String = databaseName + "-wal"
        // The filler comes after the marked job so that the marked job's pages are not the last
        // ones in the file, which a checkpoint would simply cut off.
        val markedId: Long = runBlocking {
            val marked = store.enqueue(1L, JobFixtures.MARKER_AUDIO)
            store.enqueue(1L, JobFixtures.audio(2, 6000))
            check(temp.db.checkpointTruncate()) { "whisper-server test: the checkpoint did not finish" }
            marked
        }
        val before = audioFiles(temp)
        assertTrue("whisper-server: control, the audio must be in the database file now", before.contains(databaseName))
        assertFalse("whisper-server: control, the log must be empty after the checkpoint", before.contains(walName))
        val claim: ClaimedJob? = runBlocking { store.claimNext() }
        assertNotNull("whisper-server: the marked job must be claimable", claim)
        assertEquals("whisper-server: the oldest job is the marked one", markedId, claim!!.job.id)

        runBlocking { store.complete(markedId, "a transcript") }

        assertEquals(
            "whisper-server: freed pages of the database file must not keep the audio",
            emptyList<String>(),
            audioFiles(temp),
        )
    }

    @Test
    fun `a queued job keeps its audio in the files while an older job finishes`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val older: ClaimedJob = runBlocking { JobFixtures.claimed(store, 1L, JobFixtures.audio(1)) }
        val markedId: Long = runBlocking { store.enqueue(1L, JobFixtures.MARKER_AUDIO) }

        runBlocking { store.complete(older.job.id, "the older result") }

        assertFalse(
            "whisper-server: the queued job's audio must still be in a file after another job finished",
            audioFiles(temp).isEmpty(),
        )
        val claim: ClaimedJob? = runBlocking { store.claimNext() }
        assertNotNull("whisper-server: the queued job must still be claimable", claim)
        assertEquals("whisper-server: the queued job must be the next one", markedId, claim!!.job.id)
        assertTrue(
            "whisper-server: the queued job's audio must be intact",
            JobFixtures.MARKER_AUDIO.contentEquals(claim.audio),
        )
    }

    @Test
    fun `fetching a result erases it from every file`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val claim: ClaimedJob = runBlocking { JobFixtures.claimed(store, 1L, JobFixtures.audio(1)) }
        runBlocking { store.complete(claim.job.id, JobFixtures.MARKER_RESULT) }
        assertFalse("whisper-server: control, the result must be in a file before the fetch", resultFiles(temp).isEmpty())

        val poll: JobPoll = runBlocking { store.fetch(claim.job.id, 1L) }

        assertTrue("whisper-server: the fetch must return the result", poll is JobPoll.Result)
        assertEquals("whisper-server: no file may hold a fetched result", emptyList<String>(), resultFiles(temp))
    }

    @Test
    fun `purging an expired result erases it from every file`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val claim: ClaimedJob = runBlocking { JobFixtures.claimed(store, 1L, JobFixtures.audio(1)) }
        runBlocking { store.complete(claim.job.id, JobFixtures.MARKER_RESULT) }
        assertFalse("whisper-server: control, the result must be in a file before the purge", resultFiles(temp).isEmpty())
        clock.advance(Duration.ofMillis(86_400_000L))

        val purged: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: the purge must take the one result", 1, purged)
        assertEquals("whisper-server: no file may hold a purged result", emptyList<String>(), resultFiles(temp))
    }

    @Test
    fun `recovery that fails an exhausted job erases its audio from every file`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        inTransaction(temp) { connection ->
            TestDatabases.insertJobRow(
                connection,
                TestDatabases.validJobRow(
                    mapOf<String, Any?>(
                        "status" to "processing",
                        "attempts" to 4,
                        "started_at_ms" to 1_000L,
                        "audio" to JobFixtures.MARKER_AUDIO,
                    ),
                ),
            )
        }
        assertFalse("whisper-server: control, the audio must be in a file before recovery", audioFiles(temp).isEmpty())

        val touched: Int = runBlocking { store.recoverInterrupted() }

        assertEquals("whisper-server: recovery must fail the one exhausted job", 1, touched)
        assertEquals("whisper-server: no file may hold the audio of a job recovery failed", emptyList<String>(), audioFiles(temp))
        assertEquals("whisper-server: the audio column must be NULL", 0L, rowsWithAudio(temp))
    }
}
