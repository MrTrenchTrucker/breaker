package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.ClaimedJob
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobPolicy
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.jobs.JobStoreOps
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store the worker depends on is a seam, so these tests put a fake in it: [FlakyJobStore]
 * delegates every call to the real store but throws a fixed number of times on the named
 * seam, then delegates. The worker under test is rebuilt over the fake, so the fake's throws
 * exercise exactly the wrapped store calls. The tests are the same for the unwrapped worker
 * (it fails them) and the wrapped one (it passes them); only QueueWorker.kt differs.
 */
internal class StoreAccessException(val op: String) : RuntimeException("whisper-server test helper: store.$op threw")

/**
 * A [JobStoreOps] that wraps a real [JobStore]: it throws [count] times on a seam, then
 * delegates for real, so the database still ends in the state the delegated call makes.
 * Every field is touched only from one runBlocking's coroutines, which share one thread.
 */
internal class FlakyJobStore(private val delegate: JobStore) : JobStoreOps {
    var claimThrows = 0
    var beginRetryThrows = 0
    var completeThrows = 0
    var failThrows = 0
    var recoverThrows = 0

    private val thrown = HashMap<String, Int>()

    private fun limit(op: String): Int = when (op) {
        "claimNext" -> claimThrows
        "beginRetryAttempt" -> beginRetryThrows
        "complete" -> completeThrows
        "fail" -> failThrows
        "recoverInterrupted" -> recoverThrows
        else -> 0
    }

    private suspend fun <T> guarded(op: String, block: suspend () -> T): T {
        val seen = thrown[op] ?: 0
        if (seen < limit(op)) {
            thrown[op] = seen + 1
            throw StoreAccessException(op)
        }
        return block()
    }

    /** How many times [op] threw before it started delegating. */
    fun throws(op: String): Int = thrown[op] ?: 0

    override suspend fun claimNext(): ClaimedJob? = guarded("claimNext") { delegate.claimNext() }

    override suspend fun beginRetryAttempt(jobId: Long): Int =
        guarded("beginRetryAttempt") { delegate.beginRetryAttempt(jobId) }

    override suspend fun complete(jobId: Long, result: String) =
        guarded("complete") { delegate.complete(jobId, result) }

    override suspend fun fail(jobId: Long, error: String) = guarded("fail") { delegate.fail(jobId, error) }

    override suspend fun recoverInterrupted(): Int =
        guarded("recoverInterrupted") { delegate.recoverInterrupted() }
}

/**
 * Never returns: it signals that the worker has entered the retry back-off, then suspends
 * until the worker is cancelled. A test awaits [entered], cancels, and the await of [gate]
 * throws, which is how a cancel during the back-off reaches the worker.
 */
internal class GatedPause(
    private val entered: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : Pause {
    override suspend fun pause(duration: Duration) {
        entered.complete(Unit)
        gate.await()
    }
}

internal class QueueWorkerStoreResilienceTest : TempDatabaseTest() {

    /** Rebuilds the worker of [rig] over a [fake] store that delegates to the rig's real store. */
    private fun workerOver(rig: WorkerRig, fake: FlakyJobStore): QueueWorker =
        QueueWorker(fake, rig.forwarder, rig.pause)

    // --- the five seams, each throwing once then delegating ---------------------------------

    @Test
    fun `claimNext that throws once still claims and finishes the job`() = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp())
        rig.store.enqueue(1L, JobFixtures.audio(1))
        val fake = FlakyJobStore(rig.store).apply { claimThrows = 1 }
        val worker = workerOver(rig, fake)

        assertTrue("whisper-server: runOnce must report that it handled the job", worker.runOnce())

        assertEquals("whisper-server: the job must end done", "done", WorkerFixtures.rawJob(rig.temp.db, 1L).status)
        assertEquals("whisper-server: the store must have been retried once", 1, fake.throws("claimNext"))
        assertEquals("whisper-server: one forward expected", 1, rig.forwarder.calls.size)
    }

    @Test
    fun `beginRetryAttempt that throws once still retries the job to a later success`() = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(
            openTemp(),
            script = listOf(
                WorkerFixtures.failure("one"),
                WorkerFixtures.success("second-result"),
            ),
        )
        rig.store.enqueue(1L, JobFixtures.audio(1))
        val worker = workerOver(rig, FlakyJobStore(rig.store).apply { beginRetryThrows = 1 })

        assertTrue("whisper-server: runOnce must report that it handled the job", worker.runOnce())

        assertEquals("whisper-server: the job must end done", "done", WorkerFixtures.rawJob(rig.temp.db, 1L).status)
        assertEquals("whisper-server: two forwards expected, attempt one then two", 2, rig.forwarder.calls.size)
        assertEquals(
            "whisper-server: the stored result is the one the second call returned",
            "second-result",
            WorkerFixtures.rawJob(rig.temp.db, 1L).result,
        )
    }

    @Test
    fun `complete that throws twice still finishes the job without claiming the next`() = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp())
        WorkerFixtures.enqueueJobs(rig.store, 2)
        val worker = workerOver(rig, FlakyJobStore(rig.store).apply { completeThrows = 2 })

        assertTrue("whisper-server: runOnce must report that it handled the job", worker.runOnce())

        assertEquals("whisper-server: the first job must end done", "done", WorkerFixtures.rawJob(rig.temp.db, 1L).status)
        assertEquals("whisper-server: the forwarder must have been called once", 1, rig.forwarder.calls.size)
        assertEquals("whisper-server: the next job must not be claimed while complete is retried", "queued", WorkerFixtures.rawJob(rig.temp.db, 2L).status)
        assertEquals("whisper-server: the next job must not have started an attempt", 0L, WorkerFixtures.rawJob(rig.temp.db, 2L).attempts)
    }

    @Test
    fun `complete that throws five times then succeeds finishes the job with five retries`() = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp())
        rig.store.enqueue(1L, JobFixtures.audio(1))
        val fake = FlakyJobStore(rig.store).apply { completeThrows = 5 }
        val worker = workerOver(rig, fake)

        assertTrue("whisper-server: runOnce must report that it handled the job", worker.runOnce())

        assertEquals("whisper-server: the job must end done after complete is retried", "done", WorkerFixtures.rawJob(rig.temp.db, 1L).status)
        assertEquals("whisper-server: the store complete seam must have thrown five times", 5, fake.throws("complete"))
        assertEquals("whisper-server: one RETRY_DELAY pause per failed complete call", 5, rig.pause.durations.size)
        assertTrue("whisper-server: every pause must be exactly one RETRY_DELAY", rig.pause.durations.all { it == JobPolicy.RETRY_DELAY })
    }

    @Test
    fun `fail that throws once still records the failed job`() = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp(), fallback = WorkerFixtures.failure("boom"))
        rig.store.enqueue(1L, JobFixtures.audio(1))
        val worker = workerOver(rig, FlakyJobStore(rig.store).apply { failThrows = 1 })

        assertTrue("whisper-server: runOnce must report that it handled the job", worker.runOnce())

        val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
        assertEquals("whisper-server: the job must end failed", "failed", row.status)
        assertEquals("whisper-server: the attempts column must count all four calls", 4L, row.attempts)
        assertEquals("whisper-server: the stored error must name the attempts and the reason", "failed after 4 attempts: boom", row.error)
    }

    @Test
    fun `recoverInterrupted that throws once still lets run process the queued job`() = WorkerFixtures.runBounded {
        val seen = CompletableDeferred<Long>()
        val rig = WorkerFixtures.rig(openTemp(), fallback = WorkerFixtures.reporting(seen))
        rig.store.enqueue(1L, JobFixtures.audio(1))
        val worker = workerOver(rig, FlakyJobStore(rig.store).apply { recoverThrows = 1 })
        val running = launch { worker.run() }
        try {
            assertEquals("whisper-server: the job must be forwarded once recovery succeeded", 1L, seen.await())
            assertEquals("whisper-server: the queued job must still be processed after recovery threw once", "done", WorkerFixtures.rawJob(rig.temp.db, 1L).status)
        } finally {
            running.cancelAndJoin()
        }
    }

    // --- cancellation during the retry back-off ---------------------------------------------

    @Test
    fun `cancelling while complete is retrying leaves the job processing and claims nothing`() = WorkerFixtures.runBounded {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val rig = WorkerFixtures.rig(openTemp(), script = listOf(WorkerFixtures.succeedWithOwnResult()))
        WorkerFixtures.enqueueJobs(rig.store, 2)
        val worker = QueueWorker(
            FlakyJobStore(rig.store).apply { completeThrows = 1 },
            rig.forwarder,
            GatedPause(entered, gate),
        )
        val running = launch { worker.run() }
        try {
            entered.await()
            running.cancelAndJoin()

            assertEquals("whisper-server: the job whose complete is stuck must stay processing", "processing", WorkerFixtures.rawJob(rig.temp.db, 1L).status)
            assertEquals("whisper-server: the next job must not be claimed", "queued", WorkerFixtures.rawJob(rig.temp.db, 2L).status)
            assertEquals("whisper-server: the next job must not have started an attempt", 0L, WorkerFixtures.rawJob(rig.temp.db, 2L).attempts)
            assertEquals("whisper-server: the forwarder must not have been called again", 1, rig.forwarder.calls.size)
            assertNull("whisper-server: a cancelled job stores no result", WorkerFixtures.rawJob(rig.temp.db, 1L).result)
        } finally {
            running.cancelAndJoin()
        }
    }

    @Test
    fun `cancelling while claimNext is retrying claims nothing and forwards nothing`() = WorkerFixtures.runBounded {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val rig = WorkerFixtures.rig(openTemp())
        rig.store.enqueue(1L, JobFixtures.audio(1))
        val worker = QueueWorker(
            FlakyJobStore(rig.store).apply { claimThrows = Int.MAX_VALUE },
            rig.forwarder,
            GatedPause(entered, gate),
        )
        val running = launch { worker.run() }
        try {
            entered.await()
            running.cancelAndJoin()

            assertEquals("whisper-server: a job whose claim is stuck must stay queued", "queued", WorkerFixtures.rawJob(rig.temp.db, 1L).status)
            assertEquals("whisper-server: no attempt may be counted", 0L, WorkerFixtures.rawJob(rig.temp.db, 1L).attempts)
            assertEquals("whisper-server: the forwarder must not have been called", 0, rig.forwarder.calls.size)
        } finally {
            running.cancelAndJoin()
        }
    }

    @Test
    fun `cancelling while recoverInterrupted is retrying claims nothing and forwards nothing`() = WorkerFixtures.runBounded {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val rig = WorkerFixtures.rig(openTemp(), script = listOf(WorkerFixtures.succeedWithOwnResult()))
        val processing = TestDatabases.validJobRow(
            mapOf<String, Any?>(
                "status" to "processing",
                "attempts" to 1,
                "started_at_ms" to 5L,
            ),
        )
        rig.temp.db.transaction { connection -> TestDatabases.insertJobRow(connection, processing) }
        val worker = QueueWorker(
            FlakyJobStore(rig.store).apply { recoverThrows = Int.MAX_VALUE },
            rig.forwarder,
            GatedPause(entered, gate),
        )
        val running = launch { worker.run() }
        try {
            entered.await()
            running.cancelAndJoin()

            assertEquals("whisper-server: the interrupted job must stay processing, recovery never committed", "processing", WorkerFixtures.rawJob(rig.temp.db, idOfTheProcessingRow(rig)).status)
            assertEquals("whisper-server: the forwarder must not have been called", 0, rig.forwarder.calls.size)
        } finally {
            running.cancelAndJoin()
        }
    }

    private suspend fun idOfTheProcessingRow(rig: WorkerRig): Long =
        rig.temp.db.transaction { connection -> TestDatabases.longs(connection, "SELECT id FROM jobs ORDER BY id").single() }
}
