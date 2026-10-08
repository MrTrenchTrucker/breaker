package dev.breaker.server.whisper.jobs

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.db.TestDatabases
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
import org.junit.Assert.assertSame
import org.junit.Test

/** The first fetch of a result gets it and deletes it; every other answer leaves the row alone. */
internal class JobStoreFetchTest : TempDatabaseTest() {

    private val clock = MutableClock(JobFixtures.START)
    private val calls = 8
    private val timeoutMs = 30_000L

    private fun kind(poll: JobPoll): String = poll::class.simpleName ?: "unknown"

    private fun textOf(poll: JobPoll): String {
        if (poll !is JobPoll.Result) {
            throw AssertionError("whisper-server: expected a result but got ${kind(poll)}")
        }
        return poll.text
    }

    private fun pendingStatusOf(poll: JobPoll): JobStatus {
        if (poll !is JobPoll.Pending) {
            throw AssertionError("whisper-server: expected a pending answer but got ${kind(poll)}")
        }
        return poll.status
    }

    private fun failureOf(poll: JobPoll): String {
        if (poll !is JobPoll.Failed) {
            throw AssertionError("whisper-server: expected a failed answer but got ${kind(poll)}")
        }
        return poll.error
    }

    private fun storedResults(temp: TempDatabase): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(connection, "SELECT count(*) FROM jobs WHERE result IS NOT NULL").single()
        }

    private fun snapshot(temp: TempDatabase): List<String> =
        inTransaction(temp) { connection -> TestDatabases.strings(connection, SNAPSHOT_SQL) }

    /** Runs [block] [count] times at once inside one process; the index tells the calls apart. */
    private fun <T> concurrently(count: Int, block: suspend (Int) -> T): List<T> {
        require(count in 1..calls) { "whisper-server test helper: at most $calls concurrent calls" }
        return runBlocking<List<T>> {
            withTimeout<List<T>>(timeoutMs) {
                coroutineScope<List<T>> {
                    val jobs: List<Deferred<T>> = (0 until count).map { index ->
                        async<T>(Dispatchers.Default) { block(index) }
                    }
                    jobs.awaitAll()
                }
            }
        }
    }

    @Test
    fun `the first fetch of a done job returns the exact text and the second returns result gone`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "the spoken words, exactly") }

        val first: JobPoll = runBlocking { store.fetch(id, 1L) }
        val second: JobPoll = runBlocking { store.fetch(id, 1L) }

        assertEquals("whisper-server: the first fetch must return the stored text", "the spoken words, exactly", textOf(first))
        assertSame("whisper-server: the second fetch must find the result gone", JobPoll.ResultGone, second)
    }

    @Test
    fun `the stored result is NULL in the row after the first fetch and the job stays done`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "to be deleted") }
        assertEquals("whisper-server: the result must be stored before the fetch", 1L, storedResults(temp))

        runBlocking { store.fetch(id, 1L) }

        assertEquals("whisper-server: the fetch must delete the stored result", 0L, storedResults(temp))
        val job: Job? = runBlocking { store.get(id) }
        assertNotNull("whisper-server: the row itself must stay", job)
        assertEquals("whisper-server: the job must stay done", JobStatus.DONE, job!!.status)
    }

    @Test
    fun `another owner gets not found and the real owner can still fetch the result afterwards`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "private words") }

        val stranger: JobPoll = runBlocking { store.fetch(id, 2L) }
        val owner: JobPoll = runBlocking { store.fetch(id, 1L) }

        assertSame("whisper-server: a job of another owner must look like no job", JobPoll.NotFound, stranger)
        assertEquals("whisper-server: the other owner's fetch must not consume the result", "private words", textOf(owner))
    }

    @Test
    fun `another owner gets not found for a queued job and for a failed job too`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val failedId: Long = runBlocking { JobFixtures.failedJob(store, 1L, "private failure") }
        val queuedId: Long = runBlocking { store.enqueue(1L, JobFixtures.audio(3)) }

        assertSame(
            "whisper-server: a failed job of another owner must look like no job",
            JobPoll.NotFound,
            runBlocking { store.fetch(failedId, 2L) },
        )
        assertSame(
            "whisper-server: a queued job of another owner must look like no job",
            JobPoll.NotFound,
            runBlocking { store.fetch(queuedId, 2L) },
        )
    }

    @Test
    fun `an unknown id returns not found`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking { JobFixtures.doneJob(store, 1L, "some text") }

        val poll: JobPoll = runBlocking { store.fetch(999L, 1L) }

        assertSame("whisper-server: an id that does not exist must be not found", JobPoll.NotFound, poll)
    }

    @Test
    fun `queued and processing jobs answer pending with their status and are left unchanged`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { store.enqueue(1L, JobFixtures.audio(1)) }
        val queuedBefore = snapshot(temp)

        val whileQueued: JobPoll = runBlocking { store.fetch(id, 1L) }

        assertEquals("whisper-server: a queued job must answer pending queued", JobStatus.QUEUED, pendingStatusOf(whileQueued))
        assertEquals("whisper-server: a fetch of a queued job must change nothing", queuedBefore, snapshot(temp))

        runBlocking { store.claimNext() }
        val processingBefore = snapshot(temp)
        val whileProcessing: JobPoll = runBlocking { store.fetch(id, 1L) }

        assertEquals(
            "whisper-server: a processing job must answer pending processing",
            JobStatus.PROCESSING,
            pendingStatusOf(whileProcessing),
        )
        assertEquals("whisper-server: a fetch of a processing job must change nothing", processingBefore, snapshot(temp))
    }

    @Test
    fun `a failed job returns its error every time it is asked`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.failedJob(store, 1L, "the service said no") }

        val first: JobPoll = runBlocking { store.fetch(id, 1L) }
        val second: JobPoll = runBlocking { store.fetch(id, 1L) }
        val third: JobPoll = runBlocking { store.fetch(id, 1L) }

        assertEquals("whisper-server: the first fetch must return the error", "the service said no", failureOf(first))
        assertEquals("whisper-server: the second fetch must return the same error", "the service said no", failureOf(second))
        assertEquals("whisper-server: the third fetch must return the same error", "the service said no", failureOf(third))
    }

    @Test
    fun `eight concurrent fetches of one done job give exactly one result and seven result gone`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "only one caller gets this") }

        val polls: List<JobPoll> = concurrently<JobPoll>(calls) { store.fetch(id, 1L) }

        val results = polls.filter { poll -> poll is JobPoll.Result }
        val gone = polls.filter { poll -> poll === JobPoll.ResultGone }
        assertEquals("whisper-server: exactly one caller may get the result", 1, results.size)
        assertEquals("whisper-server: the winner must get the stored text", "only one caller gets this", textOf(results[0]))
        assertEquals("whisper-server: every other caller must find it gone", calls - 1, gone.size)
    }

    @Test
    fun `a result is still returned one millisecond before 24 hours after it finished`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "just in time") }
        clock.advance(Duration.ofMillis(86_400_000L - 1L))

        val poll: JobPoll = runBlocking { store.fetch(id, 1L) }

        assertEquals("whisper-server: a result must live until 24 hours after it finished", "just in time", textOf(poll))
    }

    @Test
    fun `at exactly 24 hours the fetch answers result gone without any purge and clears the row`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "too late") }
        assertEquals("whisper-server: the result must be stored before the fetch", 1L, storedResults(temp))
        clock.advance(Duration.ofMillis(86_400_000L))

        val poll: JobPoll = runBlocking { store.fetch(id, 1L) }

        assertSame("whisper-server: at 24 hours exactly the result must be gone", JobPoll.ResultGone, poll)
        assertEquals("whisper-server: the expired result must be cleared by the fetch itself", 0L, storedResults(temp))
    }

    @Test
    fun `the text of a fetched result is not in its toString`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "confidential transcript words") }

        val poll: JobPoll = runBlocking { store.fetch(id, 1L) }

        assertEquals("whisper-server: the fetch must return the text first", "confidential transcript words", textOf(poll))
        assertFalse(
            "whisper-server: the text must not appear in toString",
            poll.toString().contains("confidential transcript words"),
        )
        assertEquals("whisper-server: toString must say the text is redacted", "JobPoll.Result(redacted)", poll.toString())
    }

    @Test
    fun `a done job whose result is already NULL answers result gone`() {
        val temp = openTemp()
        inTransaction(temp) { connection ->
            TestDatabases.insertJobRow(
                connection,
                TestDatabases.validJobRow(
                    mapOf<String, Any?>("status" to "done", "finished_at_ms" to 5L, "audio" to null),
                ),
            )
        }
        val store = JobFixtures.store(temp, clock)

        val poll: JobPoll = runBlocking { store.fetch(1L, 1L) }

        assertSame("whisper-server: a done job without a result must answer result gone", JobPoll.ResultGone, poll)
    }

    private companion object {
        const val SNAPSHOT_SQL: String =
            "SELECT id || '|' || status || '|' || attempts || '|' || ifnull(error, 'NULL') || '|' || " +
                "ifnull(result, 'NULL') || '|' || created_at_ms || '|' || ifnull(started_at_ms, 'NULL') || '|' || " +
                "ifnull(finished_at_ms, 'NULL') || '|' || " +
                "CASE WHEN audio IS NULL THEN 'NULL' ELSE hex(audio) END FROM jobs ORDER BY id"
    }
}
