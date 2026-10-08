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

internal class JobStoreTransitionsTest : TempDatabaseTest() {

    private val clock = MutableClock(JobFixtures.START)

    private suspend fun <T> raw(temp: TempDatabase, block: (Connection) -> T): T = temp.db.transaction(block)

    private suspend fun snapshot(temp: TempDatabase): List<String> = raw(temp) { connection -> JobFixtures.snapshot(connection) }

    private suspend fun strings(temp: TempDatabase, sql: String): List<String> =
        raw(temp) { connection -> TestDatabases.strings(connection, sql) }

    private suspend fun longs(temp: TempDatabase, sql: String): List<Long> =
        raw(temp) { connection -> TestDatabases.longs(connection, sql) }

    private class Candidate(val id: Long, val label: String, val statusText: String?)

    // A done job (1), a failed job (2) and a queued job (3); nothing is processing.
    // Id 99 does not exist.
    private suspend fun notProcessing(store: JobStore): List<Candidate> {
        val done = JobFixtures.doneJob(store)
        val failed = JobFixtures.failedJob(store)
        val queued = store.enqueue(1L, JobFixtures.audio(3))
        return listOf(
            Candidate(done, "a done job", "done"),
            Candidate(failed, "a failed job", "failed"),
            Candidate(queued, "a queued job", "queued"),
            Candidate(99L, "an unknown id", null),
        )
    }

    private fun assertRefusalNames(candidate: Candidate, message: String?) {
        val text = message.orEmpty()
        assertTrue("whisper-server: the message must start with the module prefix, was: $text", text.startsWith("whisper-server:"))
        assertTrue("whisper-server: the message for ${candidate.label} must name job ${candidate.id}, was: $text", text.contains("job ${candidate.id} "))
        val status = candidate.statusText
        if (status == null) {
            assertTrue("whisper-server: the message for an unknown id must say it does not exist, was: $text", text.contains("does not exist"))
        } else {
            assertTrue("whisper-server: the message for ${candidate.label} must name its status, was: $text", text.contains(status))
        }
    }

    @Test
    fun `complete is refused for a job that is not processing and changes nothing`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val candidates = notProcessing(store)
            val before = snapshot(temp)
            assertEquals("whisper-server: precondition, three rows", 3, before.size)

            for (candidate in candidates) {
                val failure = TestDatabases.expectFailure<IllegalJobTransitionException>("complete of ${candidate.label}") {
                    store.complete(candidate.id, "late result")
                }
                assertRefusalNames(candidate, failure.message)
                assertEquals("whisper-server: complete changed a row for ${candidate.label}", before, snapshot(temp))
            }
        }
    }

    @Test
    fun `fail is refused for a job that is not processing and changes nothing`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val candidates = notProcessing(store)
            val before = snapshot(temp)
            assertEquals("whisper-server: precondition, three rows", 3, before.size)

            for (candidate in candidates) {
                val failure = TestDatabases.expectFailure<IllegalJobTransitionException>("fail of ${candidate.label}") {
                    store.fail(candidate.id, "late error")
                }
                assertRefusalNames(candidate, failure.message)
                assertEquals("whisper-server: fail changed a row for ${candidate.label}", before, snapshot(temp))
            }
        }
    }

    @Test
    fun `complete stores the result, the finish time and erases the audio`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        // Escapes keep the file ASCII; the text must survive as written.
        val result = "caf\u00e9 result \u4e2d"
        runBlocking {
            val id = store.enqueue(5L, JobFixtures.audio(1))
            store.claimNext()
            clock.advance(Duration.ofSeconds(7))

            store.complete(id, result)

            val job = store.get(id)
            assertNotNull("whisper-server: the completed job must be readable", job)
            assertEquals("whisper-server: status after complete", JobStatus.DONE, job!!.status)
            assertEquals("whisper-server: finish time after complete", JobFixtures.START.plusSeconds(7), job.finishedAt)
            assertEquals("whisper-server: the start time must stay", JobFixtures.START, job.startedAt)
            assertEquals("whisper-server: attempts must stay", 1, job.attempts)
            assertNull("whisper-server: a done job has no error", job.error)
            assertEquals("whisper-server: result column", listOf(result), strings(temp, "SELECT result FROM jobs WHERE id = $id"))
            assertEquals(
                "whisper-server: finished_at_ms column",
                listOf(JobFixtures.START.plusSeconds(7).toEpochMilli()),
                longs(temp, "SELECT finished_at_ms FROM jobs WHERE id = $id"),
            )
            assertEquals(
                "whisper-server: audio must be NULL after complete",
                listOf(1L),
                longs(temp, "SELECT audio IS NULL FROM jobs WHERE id = $id"),
            )
        }
    }

    @Test
    fun `fail stores the error as given, the finish time and erases the audio`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        // The store keeps the text it is given: cutting it is the caller's job.
        val error = "e".repeat(800)
        runBlocking {
            val id = store.enqueue(5L, JobFixtures.audio(1))
            store.claimNext()
            clock.advance(Duration.ofSeconds(3))

            store.fail(id, error)

            val job = store.get(id)
            assertNotNull("whisper-server: the failed job must be readable", job)
            assertEquals("whisper-server: status after fail", JobStatus.FAILED, job!!.status)
            assertEquals("whisper-server: error after fail", error, job.error)
            assertEquals("whisper-server: finish time after fail", JobFixtures.START.plusSeconds(3), job.finishedAt)
            assertEquals("whisper-server: attempts must stay", 1, job.attempts)
            assertEquals("whisper-server: error column", listOf(error), strings(temp, "SELECT error FROM jobs WHERE id = $id"))
            assertEquals(
                "whisper-server: finished_at_ms column",
                listOf(JobFixtures.START.plusSeconds(3).toEpochMilli()),
                longs(temp, "SELECT finished_at_ms FROM jobs WHERE id = $id"),
            )
            assertEquals(
                "whisper-server: audio must be NULL after fail",
                listOf(1L),
                longs(temp, "SELECT audio IS NULL FROM jobs WHERE id = $id"),
            )
        }
    }

    @Test
    fun `a blank result is refused and the job stays processing`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val id = JobFixtures.claimed(store).job.id
            val before = snapshot(temp)

            for (blank in listOf("", "   ", "\n\t")) {
                val failure = TestDatabases.expectFailure<IllegalArgumentException>("complete with the result '$blank'") {
                    store.complete(id, blank)
                }
                assertTrue(
                    "whisper-server: the message must start with the module prefix, was: ${failure.message}",
                    failure.message.orEmpty().startsWith("whisper-server:"),
                )
            }

            assertEquals("whisper-server: a refused complete changed the row", before, snapshot(temp))
        }
    }

    @Test
    fun `a blank error is refused and the job stays processing`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val id = JobFixtures.claimed(store).job.id
            val before = snapshot(temp)

            for (blank in listOf("", "   ", "\n\t")) {
                val failure = TestDatabases.expectFailure<IllegalArgumentException>("fail with the error '$blank'") {
                    store.fail(id, blank)
                }
                assertTrue(
                    "whisper-server: the message must start with the module prefix, was: ${failure.message}",
                    failure.message.orEmpty().startsWith("whisper-server:"),
                )
            }

            assertEquals("whisper-server: a refused fail changed the row", before, snapshot(temp))
        }
    }

    @Test
    fun `a retry attempt on a processing job counts 2, 3 and 4 and the fifth is refused`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val id = JobFixtures.claimed(store).job.id
            val returned = ArrayList<Int>()
            val stored = ArrayList<Long>()
            repeat(3) {
                returned.add(store.beginRetryAttempt(id))
                stored.add(longs(temp, "SELECT attempts FROM jobs WHERE id = $id").single())
            }

            assertEquals("whisper-server: values returned by beginRetryAttempt", listOf(2, 3, 4), returned)
            assertEquals("whisper-server: attempts column after each call", listOf(2L, 3L, 4L), stored)

            val failure = TestDatabases.expectFailure<IllegalJobTransitionException>("fifth attempt") {
                store.beginRetryAttempt(id)
            }

            assertEquals(
                "whisper-server: the message of the fifth attempt",
                "whisper-server: job $id has no attempts left",
                failure.message,
            )
            assertEquals(
                "whisper-server: a refused attempt must not count",
                listOf(4L),
                longs(temp, "SELECT attempts FROM jobs WHERE id = $id"),
            )
            assertEquals("whisper-server: the job must stay processing", listOf("processing"), strings(temp, "SELECT status FROM jobs WHERE id = $id"))
        }
    }

    @Test
    fun `a retry attempt is refused for a job that is not processing and changes nothing`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val candidates = notProcessing(store)
            val before = snapshot(temp)

            for (candidate in candidates) {
                val failure = TestDatabases.expectFailure<IllegalJobTransitionException>("retry of ${candidate.label}") {
                    store.beginRetryAttempt(candidate.id)
                }
                assertEquals(
                    "whisper-server: the message for ${candidate.label}",
                    "whisper-server: job ${candidate.id} is not processing",
                    failure.message,
                )
                assertEquals("whisper-server: a refused retry changed a row for ${candidate.label}", before, snapshot(temp))
            }
        }
    }

    @Test
    fun `get answers with the metadata of a job in every status and null for an unknown id`() {
        val temp = openTemp()
        val store = JobFixtures.store(temp, clock)
        runBlocking {
            val done = JobFixtures.doneJob(store, 11L, "some result")
            clock.advance(Duration.ofSeconds(1))
            val failed = JobFixtures.failedJob(store, 12L, "some error")
            clock.advance(Duration.ofSeconds(1))
            val processing = JobFixtures.claimed(store, 13L).job.id
            val queued = store.enqueue(14L, JobFixtures.audio(4))

            val doneJob = store.get(done)!!
            val failedJob = store.get(failed)!!
            val processingJob = store.get(processing)!!
            val queuedJob = store.get(queued)!!

            assertEquals("whisper-server: done job status", JobStatus.DONE, doneJob.status)
            assertEquals("whisper-server: done job owner", 11L, doneJob.ownerAccountId)
            assertEquals("whisper-server: done job finish time", JobFixtures.START, doneJob.finishedAt)
            assertNull("whisper-server: done job error", doneJob.error)
            assertEquals("whisper-server: failed job status", JobStatus.FAILED, failedJob.status)
            assertEquals("whisper-server: failed job owner", 12L, failedJob.ownerAccountId)
            assertEquals("whisper-server: failed job error", "some error", failedJob.error)
            assertEquals("whisper-server: failed job finish time", JobFixtures.START.plusSeconds(1), failedJob.finishedAt)
            assertEquals("whisper-server: processing job status", JobStatus.PROCESSING, processingJob.status)
            assertEquals("whisper-server: processing job owner", 13L, processingJob.ownerAccountId)
            assertEquals("whisper-server: processing job attempts", 1, processingJob.attempts)
            assertEquals("whisper-server: processing job start time", JobFixtures.START.plusSeconds(2), processingJob.startedAt)
            assertNull("whisper-server: processing job finish time", processingJob.finishedAt)
            assertEquals("whisper-server: queued job status", JobStatus.QUEUED, queuedJob.status)
            assertEquals("whisper-server: queued job owner", 14L, queuedJob.ownerAccountId)
            assertEquals("whisper-server: queued job attempts", 0, queuedJob.attempts)
            assertNull("whisper-server: queued job start time", queuedJob.startedAt)
            assertNull("whisper-server: an unknown id", store.get(99L))
        }
    }
}
