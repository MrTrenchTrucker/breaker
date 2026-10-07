package dev.breaker.server.whisper.jobs

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.db.TestDatabases
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Purging deletes only results that are 24 hours old or older; the job rows stay. */
internal class JobStorePurgeTest : TempDatabaseTest() {

    private val clock = MutableClock(JobFixtures.START)

    private fun hasResult(temp: TempDatabase, id: Long): Boolean =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM jobs WHERE id = $id AND result IS NOT NULL")
                .single() == 1L
        }

    private fun snapshot(temp: TempDatabase, ids: String): List<String> =
        inTransaction(temp) { connection ->
            TestDatabases.strings(connection, SNAPSHOT_SQL + ids + ") ORDER BY id")
        }

    @Test
    fun `one millisecond before 24 hours nothing is purged and the result is still stored`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "still fresh") }
        clock.advance(Duration.ofMillis(86_400_000L - 1L))

        val purged: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: a result younger than 24 hours must not be purged", 0, purged)
        assertEquals("whisper-server: the young result must still be stored", true, hasResult(temp, id))
    }

    @Test
    fun `at exactly 24 hours the result is purged, the row stays and a fetch answers result gone`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "old enough") }
        assertEquals("whisper-server: the result must be stored before the purge", true, hasResult(temp, id))
        clock.advance(Duration.ofMillis(86_400_000L))

        val purged: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: a result of exactly 24 hours must be purged", 1, purged)
        assertEquals("whisper-server: the purged result must be NULL in the row", false, hasResult(temp, id))
        val job: Job? = runBlocking { store.get(id) }
        assertNotNull("whisper-server: the job row must stay after a purge", job)
        assertEquals("whisper-server: the purged job must stay done", JobStatus.DONE, job!!.status)
        assertEquals("whisper-server: the finish time must be kept", JobFixtures.START, job.finishedAt)
        assertSame(
            "whisper-server: a fetch after a purge must find the result gone",
            JobPoll.ResultGone,
            runBlocking { store.fetch(id, 1L) },
        )
    }

    @Test
    fun `a second purge returns 0`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking { JobFixtures.doneJob(store, 1L, "purged once") }
        clock.advance(Duration.ofMillis(86_400_000L))
        val first: Int = runBlocking { store.purgeExpired() }

        val second: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: the first purge must take the result", 1, first)
        assertEquals("whisper-server: a repeated purge has nothing left to take", 0, second)
    }

    @Test
    fun `only a done job with a stored result is purged, every other row is unchanged`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            val rows: List<Map<String, Any?>> = listOf(
                TestDatabases.validJobRow(),
                TestDatabases.validJobRow(
                    mapOf<String, Any?>("status" to "processing", "attempts" to 1, "started_at_ms" to 0L),
                ),
                TestDatabases.validJobRow(
                    mapOf<String, Any?>(
                        "status" to "failed",
                        "attempts" to 4,
                        "error" to "old failure",
                        "finished_at_ms" to 0L,
                        "audio" to null,
                    ),
                ),
                TestDatabases.validJobRow(
                    mapOf<String, Any?>("status" to "done", "finished_at_ms" to 0L, "audio" to null),
                ),
                TestDatabases.validJobRow(
                    mapOf<String, Any?>(
                        "status" to "done",
                        "result" to "old result",
                        "finished_at_ms" to 0L,
                        "audio" to null,
                    ),
                ),
            )
            for (row in rows) {
                TestDatabases.insertJobRow(connection, row)
            }
        }
        val store = JobFixtures.store(temp, clock)
        val untouchedBefore = snapshot(temp, "1, 2, 3, 4")

        val purged: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: four rows were set aside as the untouched ones", 4, untouchedBefore.size)
        assertEquals("whisper-server: only the one old stored result may be counted", 1, purged)
        assertEquals("whisper-server: the old result must be NULL", false, hasResult(temp, 5L))
        assertEquals(
            "whisper-server: queued, processing, failed and result-less done rows must not change",
            untouchedBefore,
            snapshot(temp, "1, 2, 3, 4"),
        )
    }

    @Test
    fun `only the results old enough go, judged one job at a time as the clock moves`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val older: Long = runBlocking { JobFixtures.doneJob(store, 1L, "finished first") }
        clock.advance(Duration.ofHours(10))
        val newer: Long = runBlocking { JobFixtures.doneJob(store, 1L, "finished ten hours later") }
        clock.advance(Duration.ofHours(14))

        val firstPurge: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: only the job finished 24 hours ago may be purged", 1, firstPurge)
        assertEquals("whisper-server: the older result must be gone", false, hasResult(temp, older))
        assertEquals("whisper-server: the newer result must stay", true, hasResult(temp, newer))

        clock.advance(Duration.ofHours(10))
        val secondPurge: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: the newer result goes once it is 24 hours old", 1, secondPurge)
        assertEquals("whisper-server: the newer result must now be gone", false, hasResult(temp, newer))
    }

    @Test
    fun `a result fetched before the purge is not counted`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "fetched in time") }
        val poll: JobPoll = runBlocking { store.fetch(id, 1L) }
        clock.advance(Duration.ofMillis(86_400_000L))

        val purged: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: the fetch must have returned the result", true, poll is JobPoll.Result)
        assertEquals("whisper-server: a result that is already gone must not be counted", 0, purged)
    }

    private companion object {
        const val SNAPSHOT_SQL: String =
            "SELECT id || '|' || status || '|' || attempts || '|' || ifnull(error, 'NULL') || '|' || " +
                "ifnull(result, 'NULL') || '|' || created_at_ms || '|' || ifnull(started_at_ms, 'NULL') || '|' || " +
                "ifnull(finished_at_ms, 'NULL') || '|' || " +
                "CASE WHEN audio IS NULL THEN 'NULL' ELSE hex(audio) END FROM jobs WHERE id IN ("
    }
}
