package dev.breaker.server.whisper.jobs

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.db.TestDatabases
import java.sql.Connection
import java.time.Duration
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class JobStoreClaimTest : TempDatabaseTest() {

    private val clock = MutableClock(JobFixtures.START)
    private val timeoutMs = 30_000L

    private suspend fun <T> raw(temp: TempDatabase, block: (Connection) -> T): T = temp.db.transaction(block)

    private suspend fun statusOf(temp: TempDatabase, id: Long): String =
        raw(temp) { connection -> TestDatabases.strings(connection, "SELECT status FROM jobs WHERE id = $id").single() }

    @Test
    fun `jobs are claimed oldest first whatever their owners`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            // Owners that do not rise with the ids, so an order by owner would differ.
            val owners = listOf(3L, 1L, 2L, 1L, 3L)
            val enqueued = owners.map { owner -> store.enqueue(owner, JobFixtures.audio(owner.toInt())) }
            val claimedIds = ArrayList<Long>()
            for (index in owners.indices) {
                val claim = store.claimNext()
                assertNotNull("whisper-server: claim number ${index + 1} found no job", claim)
                claimedIds.add(claim!!.job.id)
                store.complete(claim.job.id, "result-$index")
            }

            assertEquals("whisper-server: the jobs were enqueued as 1 to 5", listOf(1L, 2L, 3L, 4L, 5L), enqueued)
            assertEquals("whisper-server: the claim order must be the creation order", listOf(1L, 2L, 3L, 4L, 5L), claimedIds)
        }
    }

    @Test
    fun `an empty queue gives no job`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            assertNull("whisper-server: a claim on an empty queue", store.claimNext())
        }
    }

    @Test
    fun `a processing job blocks every claim until it is finished`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val first = store.enqueue(1L, JobFixtures.audio(1))
            val second = store.enqueue(1L, JobFixtures.audio(2))
            val third = store.enqueue(2L, JobFixtures.audio(3))
            val firstClaim = store.claimNext()
            assertEquals("whisper-server: the first claim must take the oldest job", first, firstClaim!!.job.id)

            assertNull("whisper-server: a claim while a job is processing", store.claimNext())
            assertNull("whisper-server: a second claim while a job is processing", store.claimNext())
            assertEquals("whisper-server: the second job must still be queued", "queued", statusOf(temp, second))
            assertEquals("whisper-server: the third job must still be queued", "queued", statusOf(temp, third))

            store.complete(first, "first result")
            val next = store.claimNext()

            assertNotNull("whisper-server: the claim right after the job finished", next)
            assertEquals("whisper-server: the job claimed after the first finished", second, next!!.job.id)
        }
    }

    @Test
    fun `a failed job also lets the next claim through`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val first = store.enqueue(1L, JobFixtures.audio(1))
            val second = store.enqueue(1L, JobFixtures.audio(2))
            store.claimNext()
            assertNull("whisper-server: a claim while the first job is processing", store.claimNext())

            store.fail(first, "gave up")
            val next = store.claimNext()

            assertNotNull("whisper-server: the claim right after the job failed", next)
            assertEquals("whisper-server: the job claimed after the first failed", second, next!!.job.id)
        }
    }

    @Test
    fun `a claim sets processing, one attempt and the clock time as start time`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val id = store.enqueue(9L, JobFixtures.audio(1))
            clock.advance(Duration.ofSeconds(5))

            val claim = store.claimNext()

            assertNotNull("whisper-server: the claim found no job", claim)
            val job = claim!!.job
            assertEquals("whisper-server: id of the claimed job", id, job.id)
            assertEquals("whisper-server: owner of the claimed job", 9L, job.ownerAccountId)
            assertEquals("whisper-server: status of the claimed job", JobStatus.PROCESSING, job.status)
            assertEquals("whisper-server: attempts of the claimed job", 1, job.attempts)
            assertEquals("whisper-server: start time of the claimed job", JobFixtures.START.plusSeconds(5), job.startedAt)
            assertEquals("whisper-server: creation time must not move", JobFixtures.START, job.createdAt)
            assertNull("whisper-server: a claimed job has no finish time", job.finishedAt)
            assertNull("whisper-server: a claimed job has no error", job.error)
            val rawStart = raw(temp) { connection ->
                TestDatabases.longs(connection, "SELECT started_at_ms FROM jobs WHERE id = $id")
            }
            assertEquals(
                "whisper-server: started_at_ms column",
                listOf(JobFixtures.START.plusSeconds(5).toEpochMilli()),
                rawStart,
            )
            assertEquals("whisper-server: status column", "processing", statusOf(temp, id))
        }
    }

    @Test
    fun `done and failed jobs are never claimed`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            JobFixtures.doneJob(store)
            JobFixtures.failedJob(store)
            assertNull("whisper-server: a claim with only done and failed jobs", store.claimNext())

            val queued = store.enqueue(1L, JobFixtures.audio(3))
            val claim = store.claimNext()

            assertNotNull("whisper-server: the queued job after a done and a failed one", claim)
            assertEquals("whisper-server: only the queued job may be claimed", queued, claim!!.job.id)
            assertEquals("whisper-server: the queued job is the third row", 3L, claim.job.id)
        }
    }

    /** Runs [count] claims at once inside one process, alternating between two store objects. */
    private fun claimAtOnce(first: JobStore, second: JobStore, count: Int): List<ClaimedJob?> {
        require(count in 1..8) { "whisper-server test helper: at most 8 concurrent claims" }
        return runBlocking<List<ClaimedJob?>> {
            withTimeout<List<ClaimedJob?>>(timeoutMs) {
                coroutineScope<List<ClaimedJob?>> {
                    val calls: List<Deferred<ClaimedJob?>> = (0 until count).map { index ->
                        val store = if (index % 2 == 0) first else second
                        async<ClaimedJob?>(Dispatchers.Default) { store.claimNext() }
                    }
                    calls.awaitAll()
                }
            }
        }
    }

    @Test
    fun `two store objects claiming at once on one database get exactly one job`() {
        val temp = openTemp()
        val first = JobFixtures.store(temp, clock)
        val second = JobFixtures.store(temp, clock)
        runBlocking {
            first.enqueue(1L, JobFixtures.audio(1))
            first.enqueue(1L, JobFixtures.audio(2))
        }

        val claims = claimAtOnce(first, second, 2)

        assertEquals("whisper-server: exactly one of two simultaneous claims may get a job", 1, claims.count { claim -> claim != null })
        val processing = inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM jobs WHERE status = 'processing'").single()
        }
        assertEquals("whisper-server: exactly one job may be processing", 1L, processing)
    }

    @Test
    fun `eight claims at once over eight queued jobs get exactly one job`() {
        val temp = openTemp()
        val first = JobFixtures.store(temp, clock)
        val second = JobFixtures.store(temp, clock)
        runBlocking {
            for (seed in 1..8) {
                first.enqueue(1L, JobFixtures.audio(seed))
            }
        }

        val claims = claimAtOnce(first, second, 8)

        assertEquals("whisper-server: exactly one of eight simultaneous claims may get a job", 1, claims.count { claim -> claim != null })
        val states = inTransaction(temp) { connection ->
            TestDatabases.strings(connection, "SELECT status || ':' || count(*) FROM jobs GROUP BY status ORDER BY status")
        }
        assertEquals("whisper-server: one processing job and seven queued", listOf("processing:1", "queued:7"), states)
    }

    @Test
    fun `a claimed job prints its id and never its audio`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val payload = "AUDIO-PAYLOAD-77"
        runBlocking {
            val claim = JobFixtures.claimed(store, 1L, payload.toByteArray(Charsets.US_ASCII))

            val text = claim.toString()

            assertEquals("whisper-server: the text of a claimed job", "ClaimedJob(id=1)", text)
            assertTrue("whisper-server: the text must carry the id, was: $text", text.contains("id=1"))
            assertFalse("whisper-server: the text must not carry the audio as text, was: $text", text.contains(payload))
            assertFalse(
                "whisper-server: the text must not carry the audio as numbers, was: $text",
                text.contains(claim.audio.contentToString()),
            )
        }
    }
}
