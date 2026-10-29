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

/** Purging past the short result window erases only results older than the TTL; the job rows stay,
 *  and the 24h purge ([JobStore.purgeExpired]) is a separate path this never touches. */
internal class JobStoreTtlPurgeTest : TempDatabaseTest() {

    private val clock = MutableClock(JobFixtures.START)

    private fun hasResult(temp: TempDatabase, id: Long): Boolean =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM jobs WHERE id = $id AND result IS NOT NULL")
                .single() == 1L
        }

    @Test
    fun `one millisecond before the ttl nothing is purged and the result is still stored`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "still within the window") }
        clock.advance(Duration.ofMillis(JobPolicy.RESULT_WINDOW_MS - 1L))

        val purged: Int = runBlocking { store.purgeTtl() }

        assertEquals("whisper-server: a result inside the TTL must not be purged", 0, purged)
        assertEquals("whisper-server: the young result must still be stored", true, hasResult(temp, id))
    }

    @Test
    fun `at exactly the ttl the result is purged, the row stays done and a fetch answers result gone`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "old enough for the window") }
        assertEquals("whisper-server: the result must be stored before the purge", true, hasResult(temp, id))
        clock.advance(Duration.ofMillis(JobPolicy.RESULT_WINDOW_MS))

        val purged: Int = runBlocking { store.purgeTtl() }

        assertEquals("whisper-server: a result of exactly the TTL must be purged", 1, purged)
        assertEquals("whisper-server: the purged result must be NULL in the row", false, hasResult(temp, id))
        val job: Job? = runBlocking { store.get(id) }
        assertNotNull("whisper-server: the job row must stay after a purge", job)
        assertEquals("whisper-server: the purged job must stay done", JobStatus.DONE, job!!.status)
        assertSame(
            "whisper-server: a fetch after a purge must find the result gone",
            JobPoll.ResultGone,
            runBlocking { store.fetch(id, 1L) },
        )
    }

    @Test
    fun `a younger done job survives the ttl purge while an older one is erased`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val older: Long = runBlocking { JobFixtures.doneJob(store, 1L, "finished first") }
        clock.advance(Duration.ofMinutes(11))
        val newer: Long = runBlocking { JobFixtures.doneJob(store, 1L, "finished later") }

        val purged: Int = runBlocking { store.purgeTtl() }

        assertEquals("whisper-server: only the result past the TTL may be counted", 1, purged)
        assertEquals("whisper-server: the older result must be erased", false, hasResult(temp, older))
        assertEquals("whisper-server: the younger result must survive", true, hasResult(temp, newer))
    }

    @Test
    fun `the 24h purge leaves a result past the ttl untouched`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "past the window, not past 24h") }
        clock.advance(Duration.ofHours(1))

        val purged: Int = runBlocking { store.purgeExpired() }

        assertEquals("whisper-server: the 24h purge must not touch a result younger than 24h", 0, purged)
        assertEquals(
            "whisper-server: a result past the TTL is the TTL purge's, not the 24h one's",
            true,
            hasResult(temp, id),
        )
    }
}
