package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class QueueWorkerFifoTest : TempDatabaseTest() {

    private suspend fun drain(worker: QueueWorker): Int {
        var count = 0
        while (worker.runOnce()) {
            count += 1
        }
        return count
    }

    @Test
    fun `five jobs run in creation order and each ends done with its own result`(): Unit = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp())
        val ids = WorkerFixtures.enqueueJobs(rig.store, 5)

        val answers = ArrayList<Boolean>()
        for (turn in 1..6) {
            answers.add(rig.worker.runOnce())
        }

        assertEquals(
            "whisper-server: runOnce must report work five times and then an empty queue",
            listOf(true, true, true, true, true, false),
            answers,
        )
        assertEquals(
            "whisper-server: the forwarder must see the jobs oldest first",
            listOf(1L, 2L, 3L, 4L, 5L),
            rig.forwarder.jobIds(),
        )
        for (id in ids) {
            val row = WorkerFixtures.rawJob(rig.temp.db, id)
            assertEquals("whisper-server: job $id must end done", "done", row.status)
            assertEquals("whisper-server: job $id must hold its own result", WorkerFixtures.resultFor(id), row.result)
        }
        assertEquals("whisper-server: one worker never has two calls open", 1, rig.forwarder.maxInFlight)
    }

    @Test
    fun `the second job stays queued while the first job is inside the forwarder`(): Unit = WorkerFixtures.runBounded {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rig = WorkerFixtures.rig(
            openTemp(),
            script = listOf(WorkerFixtures.blockedOn(entered, release, "first-result")),
        )
        val ids = WorkerFixtures.enqueueJobs(rig.store, 2)
        val running = async { rig.worker.runOnce() }
        try {
            entered.await()

            assertEquals(
                "whisper-server: the first job must be processing during its call",
                JobStatus.PROCESSING,
                WorkerFixtures.statusOf(rig.store, ids[0]),
            )
            assertEquals(
                "whisper-server: the second job must still be queued during the first call",
                JobStatus.QUEUED,
                WorkerFixtures.statusOf(rig.store, ids[1]),
            )
            assertEquals(
                "whisper-server: the claim of the first job must not count an attempt for the second",
                0L,
                WorkerFixtures.rawJob(rig.temp.db, ids[1]).attempts,
            )
            assertEquals("whisper-server: the forwarder must have been called once", 1, rig.forwarder.calls.size)

            release.complete(Unit)
            assertTrue("whisper-server: the first runOnce must report work", running.await())
            assertEquals(
                "whisper-server: the first job must be done after its call returned",
                JobStatus.DONE,
                WorkerFixtures.statusOf(rig.store, ids[0]),
            )
            assertEquals(
                "whisper-server: one runOnce takes one job only",
                JobStatus.QUEUED,
                WorkerFixtures.statusOf(rig.store, ids[1]),
            )
        } finally {
            running.cancel()
        }
    }

    @Test
    fun `a second worker cannot start a job while the first worker is inside the forwarder`(): Unit =
        WorkerFixtures.runBounded {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val rig = WorkerFixtures.rig(
                openTemp(),
                script = listOf(WorkerFixtures.blockedOn(entered, release, "first-result")),
            )
            val other = QueueWorker(rig.store, rig.forwarder, rig.pause)
            WorkerFixtures.enqueueJobs(rig.store, 3)
            val first = async { rig.worker.runOnce() }
            try {
                entered.await()

                assertFalse(
                    "whisper-server: a second worker must find nothing to claim while a job is processing",
                    other.runOnce(),
                )
                assertEquals(
                    "whisper-server: only the first job may have been forwarded",
                    1,
                    rig.forwarder.calls.size,
                )
                assertEquals("whisper-server: never two calls open at once", 1, rig.forwarder.maxInFlight)

                release.complete(Unit)
                assertTrue("whisper-server: the first worker must finish its job", first.await())
                drain(other)

                assertEquals(
                    "whisper-server: every job is forwarded once, oldest first",
                    listOf(1L, 2L, 3L),
                    rig.forwarder.jobIds(),
                )
                assertEquals("whisper-server: never two calls open at once", 1, rig.forwarder.maxInFlight)
            } finally {
                first.cancel()
            }
        }

    @Test
    fun `two workers draining one queue forward every job exactly once`(): Unit = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp(), fallback = WorkerFixtures.yieldingSuccess())
        val other = QueueWorker(rig.store, rig.forwarder, rig.pause)
        val ids = WorkerFixtures.enqueueJobs(rig.store, 6)

        val loops = listOf(async { drain(rig.worker) }, async { drain(other) })
        var handled = 0
        for (loop in loops) {
            handled += loop.await()
        }

        assertEquals("whisper-server: the two workers together handle six jobs", 6, handled)
        assertEquals(
            "whisper-server: each job is forwarded exactly once, oldest first",
            listOf(1L, 2L, 3L, 4L, 5L, 6L),
            rig.forwarder.jobIds(),
        )
        assertEquals("whisper-server: never two calls open at once", 1, rig.forwarder.maxInFlight)
        for (id in ids) {
            assertEquals(
                "whisper-server: job $id must end done",
                "done",
                WorkerFixtures.rawJob(rig.temp.db, id).status,
            )
        }
    }

    @Test
    fun `the forwarder receives the audio of the job it is called for`(): Unit = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp())
        val ids = WorkerFixtures.enqueueJobs(rig.store, 3)

        drain(rig.worker)

        assertEquals("whisper-server: three calls expected", 3, rig.forwarder.calls.size)
        for (index in 0..2) {
            val call = rig.forwarder.calls[index]
            assertEquals("whisper-server: call ${index + 1} must be for its own job", ids[index], call.jobId)
            assertArrayEquals(
                "whisper-server: call ${index + 1} must carry the audio that was enqueued for that job",
                JobFixtures.audio(index + 1),
                call.audio,
            )
        }
    }
}
