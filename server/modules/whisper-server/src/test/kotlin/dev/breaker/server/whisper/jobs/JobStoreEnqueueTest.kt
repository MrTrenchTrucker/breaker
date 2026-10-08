package dev.breaker.server.whisper.jobs

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.db.TestDatabases
import java.sql.Connection
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class JobStoreEnqueueTest : TempDatabaseTest() {

    private val clock = MutableClock(JobFixtures.START)

    private suspend fun <T> raw(temp: TempDatabase, block: (Connection) -> T): T = temp.db.transaction(block)

    @Test
    fun `ids increase with every enqueue and match the stored rows`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val ids = listOf(
                store.enqueue(1L, JobFixtures.audio(1)),
                store.enqueue(1L, JobFixtures.audio(2)),
                store.enqueue(1L, JobFixtures.audio(3)),
            )

            assertEquals("whisper-server: the ids returned by enqueue", listOf(1L, 2L, 3L), ids)
            val stored = raw(temp) { connection -> TestDatabases.longs(connection, "SELECT id FROM jobs ORDER BY id") }
            assertEquals("whisper-server: the returned ids must be the stored ids", ids, stored)
        }
    }

    @Test
    fun `a new job is queued with no attempts, the clock time as creation time and its owner`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val id = store.enqueue(42L, JobFixtures.audio(1))

            val job = store.get(id)
            assertNotNull("whisper-server: the enqueued job must be readable", job)
            assertEquals("whisper-server: status of a new job", JobStatus.QUEUED, job!!.status)
            assertEquals("whisper-server: attempts of a new job", 0, job.attempts)
            assertEquals("whisper-server: owner of a new job", 42L, job.ownerAccountId)
            assertEquals("whisper-server: creation time of a new job", JobFixtures.START, job.createdAt)
            assertNull("whisper-server: a new job has no start time", job.startedAt)
            assertNull("whisper-server: a new job has no finish time", job.finishedAt)
            assertNull("whisper-server: a new job has no error", job.error)
            val rawTimes = raw(temp) { connection ->
                TestDatabases.longs(connection, "SELECT created_at_ms FROM jobs WHERE id = $id")
            }
            assertEquals("whisper-server: created_at_ms column", listOf(JobFixtures.START.toEpochMilli()), rawTimes)
            val rawStatus = raw(temp) { connection ->
                TestDatabases.strings(connection, "SELECT status FROM jobs WHERE id = $id")
            }
            assertEquals("whisper-server: status column", listOf("queued"), rawStatus)
        }
    }

    @Test
    fun `the creation time follows the clock`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val first = store.enqueue(1L, JobFixtures.audio(1))
            clock.advance(Duration.ofSeconds(90))
            val second = store.enqueue(1L, JobFixtures.audio(2))

            assertEquals("whisper-server: creation time of the first job", JobFixtures.START, store.get(first)!!.createdAt)
            assertEquals(
                "whisper-server: creation time of the second job",
                JobFixtures.START.plusSeconds(90),
                store.get(second)!!.createdAt,
            )
        }
    }

    @Test
    fun `the audio comes back byte for byte through the claim`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        // Every byte value, so a signed/unsigned slip or a cut at a zero byte shows.
        val audio = ByteArray(1024) { index -> (index % 256).toByte() }
        runBlocking {
            store.enqueue(1L, audio)

            val claim = store.claimNext()

            assertNotNull("whisper-server: the enqueued job must be claimable", claim)
            assertTrue("whisper-server: the claimed audio differs from the enqueued audio", audio.contentEquals(claim!!.audio))
            val storedLength = raw(temp) { connection ->
                TestDatabases.longs(connection, "SELECT length(audio) FROM jobs WHERE id = 1")
            }
            assertEquals("whisper-server: stored audio length", listOf(1024L), storedLength)
        }
    }

    @Test
    fun `an empty audio array is refused and leaves no row`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val failure = TestDatabases.expectFailure<IllegalArgumentException>("enqueue of empty audio") {
                store.enqueue(1L, ByteArray(0))
            }

            assertEquals("whisper-server: the refusal message", "whisper-server: the audio is empty", failure.message)
            val count = raw(temp) { connection -> TestDatabases.longs(connection, "SELECT count(*) FROM jobs").single() }
            assertEquals("whisper-server: a refused enqueue must leave no row", 0L, count)
        }
    }

    @Test
    fun `two owners can enqueue and each job keeps its own owner`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val first = store.enqueue(7L, JobFixtures.audio(1))
            val second = store.enqueue(8L, JobFixtures.audio(2))

            assertEquals("whisper-server: two enqueues must give two ids", listOf(1L, 2L), listOf(first, second))
            assertEquals("whisper-server: owner of the first job", 7L, store.get(first)!!.ownerAccountId)
            assertEquals("whisper-server: owner of the second job", 8L, store.get(second)!!.ownerAccountId)
        }
    }

    @Test
    fun `an enqueue changes no other row`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            JobFixtures.doneJob(store, 1L, "first result")
            JobFixtures.failedJob(store, 2L, "second error")
            JobFixtures.claimed(store, 3L, JobFixtures.audio(3))
            store.enqueue(4L, JobFixtures.audio(4))
            val before = raw(temp) { connection -> JobFixtures.snapshot(connection) }
            assertEquals("whisper-server: precondition, four rows before the enqueue", 4, before.size)

            store.enqueue(5L, JobFixtures.audio(5))

            val after = raw(temp) { connection -> JobFixtures.snapshot(connection) }
            assertEquals("whisper-server: one row more after the enqueue", 5, after.size)
            assertEquals("whisper-server: an enqueue changed an existing row", before, after.take(4))
        }
    }
}
