package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.db.TestDatabases
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

internal class QueueWorkerRestartTest : TempDatabaseTest() {

    // Runs the worker until its forwarder call is open, then cancels it: the way a
    // process stop looks to the database, a job left processing.
    private suspend fun CoroutineScope.stopWhileForwarding(rig: WorkerRig, entered: CompletableDeferred<Unit>) {
        val running = launch { rig.worker.run() }
        entered.await()
        running.cancelAndJoin()
    }

    @Test
    fun `run processes the jobs that are already queued at start in order`(): Unit = WorkerFixtures.runBounded {
        val finalEntered = CompletableDeferred<Unit>()
        val rig = WorkerFixtures.rig(
            openTemp(),
            script = listOf(
                WorkerFixtures.succeedWithOwnResult(),
                WorkerFixtures.succeedWithOwnResult(),
                WorkerFixtures.succeedWithOwnResult(),
                WorkerFixtures.suspendForever(finalEntered),
            ),
        )
        // The fourth job is held open on purpose: a job can only be claimed once the one
        // before it is done, so its call being open proves jobs one to three are finished.
        WorkerFixtures.enqueueJobs(rig.store, 4)
        val running = launch { rig.worker.run() }
        try {
            finalEntered.await()

            assertEquals(
                "whisper-server: run must forward the queued jobs oldest first",
                listOf(1L, 2L, 3L, 4L),
                rig.forwarder.jobIds(),
            )
            for (id in 1L..3L) {
                val row = WorkerFixtures.rawJob(rig.temp.db, id)
                assertEquals("whisper-server: job $id must be done", "done", row.status)
                assertEquals("whisper-server: job $id must hold its result", WorkerFixtures.resultFor(id), row.result)
            }
        } finally {
            running.cancelAndJoin()
        }
    }

    @Test
    fun `cancelling the worker mid-job leaves the job processing and is not a failure`(): Unit =
        WorkerFixtures.runBounded {
            val entered = CompletableDeferred<Unit>()
            val rig = WorkerFixtures.rig(openTemp(), script = listOf(WorkerFixtures.suspendForever(entered)))
            WorkerFixtures.enqueueJobs(rig.store, 3)

            stopWhileForwarding(rig, entered)

            val first = WorkerFixtures.rawJob(rig.temp.db, 1L)
            assertEquals("whisper-server: the interrupted job must still be processing", "processing", first.status)
            assertEquals("whisper-server: only the first attempt was made", 1L, first.attempts)
            assertNull("whisper-server: a cancel must not store an error", first.error)
            assertEquals(
                "whisper-server: a cancel must not start a retry pause",
                emptyList<java.time.Duration>(),
                rig.pause.durations,
            )
            assertEquals("whisper-server: the forwarder was called once", 1, rig.forwarder.calls.size)
            assertEquals("whisper-server: job 2 is untouched", "queued", WorkerFixtures.rawJob(rig.temp.db, 2L).status)
            assertEquals("whisper-server: job 3 is untouched", "queued", WorkerFixtures.rawJob(rig.temp.db, 3L).status)
        }

    @Test
    fun `after a restart the interrupted job runs first and all jobs complete`(): Unit = WorkerFixtures.runBounded {
        val clock = MutableClock(JobFixtures.START)
        val entered = CompletableDeferred<Unit>()
        val before = WorkerFixtures.rig(openTemp(), clock, script = listOf(WorkerFixtures.suspendForever(entered)))
        WorkerFixtures.enqueueJobs(before.store, 3)
        stopWhileForwarding(before, entered)

        val finalEntered = CompletableDeferred<Unit>()
        val after = WorkerFixtures.rig(
            reopen(before.temp),
            clock,
            script = listOf(
                WorkerFixtures.succeedWithOwnResult(),
                WorkerFixtures.succeedWithOwnResult(),
                WorkerFixtures.succeedWithOwnResult(),
                WorkerFixtures.suspendForever(finalEntered),
            ),
        )
        // The fourth job is held open on purpose, see the first test of this class.
        after.store.enqueue(1L, JobFixtures.audio(4))
        val running = launch { after.worker.run() }
        try {
            finalEntered.await()

            assertEquals(
                "whisper-server: the interrupted job must run before the jobs queued after it",
                listOf(1L, 2L, 3L, 4L),
                after.forwarder.jobIds(),
            )
            for (id in 1L..3L) {
                assertEquals(
                    "whisper-server: job $id must be done",
                    "done",
                    WorkerFixtures.rawJob(after.temp.db, id).status,
                )
            }
            assertEquals(
                "whisper-server: the interrupted job keeps its first attempt and adds one",
                2L,
                WorkerFixtures.rawJob(after.temp.db, 1L).attempts,
            )
        } finally {
            running.cancelAndJoin()
        }
    }

    @Test
    fun `a job left processing with four attempts is failed at start and never forwarded`(): Unit =
        WorkerFixtures.runBounded {
            val stopped = openTemp()
            val exhausted: Map<String, Any?> = TestDatabases.validJobRow(
                mapOf<String, Any?>("status" to "processing", "attempts" to 4, "started_at_ms" to 5L),
            )
            stopped.db.transaction { connection -> TestDatabases.insertJobRow(connection, exhausted) }

            val entered = CompletableDeferred<Unit>()
            val rig = WorkerFixtures.rig(reopen(stopped), script = listOf(WorkerFixtures.suspendForever(entered)))
            val laterId = rig.store.enqueue(1L, JobFixtures.audio(2))
            val running = launch { rig.worker.run() }
            try {
                entered.await()

                assertEquals(
                    "whisper-server: only the job queued after the exhausted one is forwarded",
                    listOf(laterId),
                    rig.forwarder.jobIds(),
                )
                val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
                assertEquals("whisper-server: the exhausted job must be failed", "failed", row.status)
                assertEquals(
                    "whisper-server: the exhausted job must say why",
                    "the service call was interrupted by a restart and no retries are left",
                    row.error,
                )
                assertFalse("whisper-server: the exhausted job must have no audio", row.hasAudio)
            } finally {
                running.cancelAndJoin()
            }
        }

    @Test
    fun `after a restart the interrupted job retries with the correct attempt numbers`(): Unit =
        WorkerFixtures.runBounded {
            val clock = MutableClock(JobFixtures.START)
            val entered = CompletableDeferred<Unit>()
            val before = WorkerFixtures.rig(openTemp(), clock, script = listOf(WorkerFixtures.suspendForever(entered)))
            WorkerFixtures.enqueueJobs(before.store, 1)
            stopWhileForwarding(before, entered)

            val job2Done = CompletableDeferred<Unit>()
            val after = WorkerFixtures.rig(
                reopen(before.temp),
                clock,
                script = listOf(
                    WorkerFixtures.failure("test failure"),
                    WorkerFixtures.failure("test failure"),
                    WorkerFixtures.failure("test failure"),
                    { request -> job2Done.complete(Unit); ForwardOutcome.Success(WorkerFixtures.resultFor(request.jobId)) },
                ),
            )
            after.store.enqueue(1L, JobFixtures.audio(2))
            val running = launch { after.worker.run() }
            try {
                job2Done.await()
                WorkerFixtures.laneBarrier(after.store)

                assertEquals(
                    "whisper-server: the interrupted job must retry with attempts 2, 3, 4",
                    listOf(Pair(1L, 2), Pair(1L, 3), Pair(1L, 4)),
                    after.forwarder.pairs().take(3),
                )
                assertEquals(
                    "whisper-server: two pauses between three retries",
                    2,
                    after.pause.durations.size,
                )
                val row = WorkerFixtures.rawJob(after.temp.db, 1L)
                assertEquals("whisper-server: the job must be failed", "failed", row.status)
                assertEquals(
                    "whisper-server: the failure text must start with the prefix",
                    true,
                    row.error!!.startsWith("failed after 4 attempts:"),
                )
                assertEquals(
                    "whisper-server: run() must stay alive and process the second job",
                    listOf(2L),
                    after.forwarder.jobIds().drop(3),
                )
            } finally {
                running.cancelAndJoin()
            }
        }

    @Test
    fun `a job recovered at MAX-1 attempts gets exactly one more forward then fails`(): Unit =
        WorkerFixtures.runBounded {
            val stopped = openTemp()
            val atMaxMinusOne: Map<String, Any?> = TestDatabases.validJobRow(
                mapOf<String, Any?>("status" to "processing", "attempts" to 3, "started_at_ms" to 5L),
            )
            stopped.db.transaction { connection -> TestDatabases.insertJobRow(connection, atMaxMinusOne) }

            val entered = CompletableDeferred<Unit>()
            val rig = WorkerFixtures.rig(
                reopen(stopped),
                script = listOf(
                    WorkerFixtures.failure("test failure"),
                    WorkerFixtures.suspendForever(entered),
                ),
            )
            val laterId = rig.store.enqueue(1L, JobFixtures.audio(2))
            val running = launch { rig.worker.run() }
            try {
                entered.await()

                assertEquals(
                    "whisper-server: exactly one forward at attempt 4",
                    listOf(Pair(1L, 4)),
                    rig.forwarder.pairs().filter { it.first == 1L },
                )
                val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
                assertEquals("whisper-server: the job must be failed", "failed", row.status)
                assertEquals(
                    "whisper-server: run() must stay alive",
                    listOf(laterId),
                    rig.forwarder.jobIds().drop(1),
                )
            } finally {
                running.cancelAndJoin()
            }
        }

    @Test
    fun `the stored failure text is bounded at 500 characters even with a long reason`(): Unit =
        WorkerFixtures.runBounded {
            val longReason = "x".repeat(600)
            val jobDone = CompletableDeferred<Unit>()
            val rig = WorkerFixtures.rig(
                openTemp(),
                script = listOf(
                    WorkerFixtures.failure(longReason),
                    WorkerFixtures.failure(longReason),
                    WorkerFixtures.failure(longReason),
                    { request -> jobDone.complete(Unit); ForwardOutcome.Failure(longReason) },
                ),
            )
            WorkerFixtures.enqueueJobs(rig.store, 1)
            val running = launch { rig.worker.run() }
            try {
                jobDone.await()
                WorkerFixtures.laneBarrier(rig.store)

                val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
                assertEquals("whisper-server: the job must be failed", "failed", row.status)
                assertEquals(
                    "whisper-server: the stored error must be bounded at 500",
                    true,
                    row.error!!.length <= 500,
                )
                assertEquals(
                    "whisper-server: the error must start with the prefix",
                    true,
                    row.error!!.startsWith("failed after 4 attempts:"),
                )
            } finally {
                running.cancelAndJoin()
            }
        }

    @Test
    fun `a wake-up after an enqueue makes an idle worker process the job`(): Unit = WorkerFixtures.runBounded {
        val clock = ReadSignalClock(MutableClock(JobFixtures.START))
        val seen = CompletableDeferred<Long>()
        val rig = WorkerFixtures.rig(openTemp(), clock, fallback = WorkerFixtures.reporting(seen))
        val running = launch { rig.worker.run() }
        try {
            // Read one is the recovery at start, read two is the first claim, which finds
            // nothing. The barrier then waits for that claim to finish, so the worker is
            // waiting for a wake-up when the job arrives.
            clock.readReached(2).await()
            WorkerFixtures.laneBarrier(rig.store)

            val id = rig.store.enqueue(1L, JobFixtures.audio(1))
            rig.worker.wake()

            assertEquals("whisper-server: the woken worker must forward the new job", id, seen.await())
        } finally {
            running.cancelAndJoin()
        }
    }

    @Test
    fun `a wake-up sent while a job is running is kept and makes the worker look once more`(): Unit =
        WorkerFixtures.runBounded {
            val clock = ReadSignalClock(MutableClock(JobFixtures.START))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val rig = WorkerFixtures.rig(
                openTemp(),
                clock,
                script = listOf(WorkerFixtures.blockedOn(entered, release, "first-result")),
            )
            rig.store.enqueue(1L, JobFixtures.audio(1))
            val start = clock.readCount()
            val running = launch { rig.worker.run() }
            try {
                entered.await()
                rig.worker.wake()
                release.complete(Unit)

                // Reads after the enqueue: recovery, claim of the job, completion, the claim
                // that finds nothing, and one more claim only because the wake-up was kept.
                clock.readReached(start + 5).await()
                WorkerFixtures.laneBarrier(rig.store)

                assertEquals(
                    "whisper-server: the kept wake-up must cause exactly one more look for work",
                    start + 5,
                    clock.readCount(),
                )
            } finally {
                running.cancelAndJoin()
            }
        }

    @Test
    fun `without a wake-up the worker looks for work once after a job and then waits`(): Unit =
        WorkerFixtures.runBounded {
            val clock = ReadSignalClock(MutableClock(JobFixtures.START))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val rig = WorkerFixtures.rig(
                openTemp(),
                clock,
                script = listOf(WorkerFixtures.blockedOn(entered, release, "first-result")),
            )
            rig.store.enqueue(1L, JobFixtures.audio(1))
            val start = clock.readCount()
            val running = launch { rig.worker.run() }
            try {
                entered.await()
                release.complete(Unit)

                clock.readReached(start + 4).await()
                WorkerFixtures.laneBarrier(rig.store)

                assertEquals(
                    "whisper-server: an idle worker must wait instead of polling",
                    start + 4,
                    clock.readCount(),
                )
                assertEquals(
                    "whisper-server: the job was finished",
                    "done",
                    WorkerFixtures.rawJob(rig.temp.db, 1L).status,
                )
            } finally {
                running.cancelAndJoin()
            }
        }

    @Test
    fun `a wake-up and a job that exist before run starts are processed`(): Unit = WorkerFixtures.runBounded {
        val seen = CompletableDeferred<Long>()
        val rig = WorkerFixtures.rig(openTemp(), fallback = WorkerFixtures.reporting(seen))
        val id = rig.store.enqueue(1L, JobFixtures.audio(1))
        rig.worker.wake()
        val running = launch { rig.worker.run() }
        try {
            assertEquals("whisper-server: the queued job must be forwarded", id, seen.await())
        } finally {
            running.cancelAndJoin()
        }
    }
}
