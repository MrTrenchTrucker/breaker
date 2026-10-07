package dev.breaker.server.whisper.jobs

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.db.TestDatabases
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop (the connection closed, the file kept) can leave one job processing. Recovery gives
 * it back to the queue at its old place, or fails it when it has no attempts left.
 */
internal class JobStoreRecoveryTest : TempDatabaseTest() {

    private val clock = MutableClock(JobFixtures.START)

    private fun snapshot(temp: TempDatabase, fromId: Long = 1L): List<String> =
        inTransaction(temp) { connection ->
            TestDatabases.strings(connection, SNAPSHOT_SQL + fromId + " ORDER BY id")
        }

    private fun insertRows(temp: TempDatabase, rows: List<Map<String, Any?>>) {
        inTransaction(temp) { connection ->
            for (row in rows) {
                TestDatabases.insertJobRow(connection, row)
            }
        }
    }

    private fun processingRow(attempts: Int): Map<String, Any?> =
        TestDatabases.validJobRow(
            mapOf<String, Any?>("status" to "processing", "attempts" to attempts, "started_at_ms" to 1_000L),
        )

    @Test
    fun `a job left processing is queued again with its attempts, its audio and no start time`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking {
            val claim = JobFixtures.claimed(store, 1L, JobFixtures.audio(7, 64))
            assertEquals(
                "whisper-server: the second attempt must be counted before the stop",
                2,
                store.beginRetryAttempt(claim.job.id),
            )
            claim.job.id
        }

        val restarted = reopen(temp)
        val after = JobFixtures.store(restarted, clock)
        val touched: Int = runBlocking { after.recoverInterrupted() }
        val job: Job? = runBlocking { after.get(id) }

        assertEquals("whisper-server: recovery must report the one job it touched", 1, touched)
        assertNotNull("whisper-server: the interrupted job must still exist", job)
        assertEquals("whisper-server: the interrupted job must be queued again", JobStatus.QUEUED, job!!.status)
        assertEquals("whisper-server: the attempts already used must be kept", 2, job.attempts)
        assertNull("whisper-server: a queued job has no start time", job.startedAt)
        assertNull("whisper-server: a queued job has no finish time", job.finishedAt)
        assertNull("whisper-server: a queued job has no error", job.error)
        assertEquals(
            "whisper-server: the audio must still be in the row",
            listOf(64L),
            inTransaction(restarted) { connection ->
                TestDatabases.longs(connection, "SELECT length(audio) FROM jobs WHERE id = $id")
            },
        )
    }

    @Test
    fun `an interrupted job runs first after recovery, not after the jobs queued behind it`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val audio = JobFixtures.audio(5, 32)
        val ids: List<Long> = runBlocking {
            val claim = JobFixtures.claimed(store, 1L, audio)
            val later = store.enqueue(1L, JobFixtures.audio(6, 32))
            listOf(claim.job.id, later)
        }

        val after = JobFixtures.store(reopen(temp), clock)
        runBlocking { after.recoverInterrupted() }
        val claim: ClaimedJob? = runBlocking { after.claimNext() }

        assertNotNull("whisper-server: a recovered job must be claimable", claim)
        assertEquals("whisper-server: the interrupted job must run before the later one", ids[0], claim!!.job.id)
        assertTrue("whisper-server: the audio must come back byte for byte", audio.contentEquals(claim.audio))
        assertEquals(
            "whisper-server: the kept attempt and the new claim are both counted",
            2,
            claim.job.attempts,
        )
    }

    @Test
    fun `with one processing job and two queued jobs the claim order after recovery is 1, 2, 3`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            JobFixtures.claimed(store, 1L, JobFixtures.audio(1))
            store.enqueue(2L, JobFixtures.audio(2))
            store.enqueue(1L, JobFixtures.audio(3))
        }

        val after = JobFixtures.store(reopen(temp), clock)
        val order = ArrayList<Long>()
        runBlocking {
            after.recoverInterrupted()
            for (step in 1..3) {
                val claim = after.claimNext()
                assertNotNull("whisper-server: claim $step must find a job", claim)
                order.add(claim!!.job.id)
                after.complete(claim.job.id, "result $step")
            }
        }

        assertEquals("whisper-server: jobs must run in creation order after a restart", listOf(1L, 2L, 3L), order)
        assertNull("whisper-server: nothing may be left to claim", runBlocking { after.claimNext() })
    }

    @Test
    fun `a processing job with four attempts used is failed with the restart text and never claimed`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking {
            val claim = JobFixtures.claimed(store, 1L, JobFixtures.audio(4, 48))
            repeat(3) { store.beginRetryAttempt(claim.job.id) }
            claim.job.id
        }

        val restarted = reopen(temp)
        val after = JobFixtures.store(restarted, clock)
        clock.advance(Duration.ofMinutes(5))
        val touched: Int = runBlocking { after.recoverInterrupted() }
        val job: Job? = runBlocking { after.get(id) }

        assertEquals("whisper-server: recovery must report the one job it failed", 1, touched)
        assertEquals("whisper-server: an exhausted job must be failed", JobStatus.FAILED, job!!.status)
        assertEquals("whisper-server: the attempts must stay at four", 4, job.attempts)
        assertEquals(
            "whisper-server: the failure text must say the restart interrupted the call",
            "the service call was interrupted by a restart and no retries are left",
            job.error,
        )
        assertEquals("whisper-server: the finish time must be the recovery time", clock.instant(), job.finishedAt)
        assertEquals(
            "whisper-server: a failed job must not keep its audio",
            listOf(0L),
            inTransaction(restarted) { connection ->
                TestDatabases.longs(connection, "SELECT count(*) FROM jobs WHERE audio IS NOT NULL")
            },
        )
        assertNull("whisper-server: a failed job must never be claimed", runBlocking { after.claimNext() })
    }

    @Test
    fun `recovery fails the job at four attempts, requeues the others and counts every row it touched`() {
        val temp = openTemp()
        insertRows(
            temp,
            listOf(
                processingRow(2),
                processingRow(4),
                processingRow(3),
                TestDatabases.validJobRow(),
                TestDatabases.validJobRow(
                    mapOf<String, Any?>(
                        "status" to "done",
                        "attempts" to 1,
                        "result" to "kept result",
                        "created_at_ms" to 10L,
                        "started_at_ms" to 20L,
                        "finished_at_ms" to 30L,
                        "audio" to null,
                    ),
                ),
                TestDatabases.validJobRow(
                    mapOf<String, Any?>(
                        "status" to "failed",
                        "attempts" to 4,
                        "error" to "kept error",
                        "created_at_ms" to 10L,
                        "started_at_ms" to 20L,
                        "finished_at_ms" to 30L,
                        "audio" to null,
                    ),
                ),
            ),
        )
        val untouchedBefore = snapshot(temp, 4L)
        val store = JobFixtures.store(temp, clock)

        val touched: Int = runBlocking { store.recoverInterrupted() }

        assertEquals("whisper-server: two requeued rows and one failed row are three touched rows", 3, touched)
        assertEquals(
            "whisper-server: three attempts used leaves one more, four used leaves none",
            listOf("queued", "failed", "queued", "queued", "done", "failed"),
            inTransaction(temp) { connection ->
                TestDatabases.strings(connection, "SELECT status FROM jobs ORDER BY id")
            },
        )
        assertEquals(
            "whisper-server: recovery must keep every attempt count",
            listOf(2L, 4L, 3L, 0L, 1L, 4L),
            inTransaction(temp) { connection ->
                TestDatabases.longs(connection, "SELECT attempts FROM jobs ORDER BY id")
            },
        )
        assertEquals("whisper-server: three rows were set aside as the untouched ones", 3, untouchedBefore.size)
        assertEquals(
            "whisper-server: queued, done and failed rows must not change",
            untouchedBefore,
            snapshot(temp, 4L),
        )
    }

    @Test
    fun `recovery of a database with nothing processing returns 0 and changes nothing`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            JobFixtures.doneJob(store, 1L, "a finished result")
            JobFixtures.failedJob(store, 1L, "a recorded failure")
            store.enqueue(2L, JobFixtures.audio(9))
        }
        val before = snapshot(temp)

        val touched: Int = runBlocking { store.recoverInterrupted() }

        assertEquals("whisper-server: three rows exist before recovery", 3, before.size)
        assertEquals("whisper-server: recovery must touch no row when nothing is processing", 0, touched)
        assertEquals("whisper-server: no row may change", before, snapshot(temp))
    }

    private companion object {
        const val SNAPSHOT_SQL: String =
            "SELECT id || '|' || status || '|' || attempts || '|' || ifnull(error, 'NULL') || '|' || " +
                "ifnull(result, 'NULL') || '|' || created_at_ms || '|' || ifnull(started_at_ms, 'NULL') || '|' || " +
                "ifnull(finished_at_ms, 'NULL') || '|' || " +
                "CASE WHEN audio IS NULL THEN 'NULL' ELSE hex(audio) END FROM jobs WHERE id >= "
    }
}
