package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStatus
import java.time.Duration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class QueueWorkerRetryTest : TempDatabaseTest() {

    private fun failingRig(watched: List<Long> = emptyList()): WorkerRig =
        WorkerFixtures.rig(openTemp(), fallback = WorkerFixtures.failure("boom"), watched = watched)

    @Test
    fun `a job whose every call fails is forwarded four times with attempts one to four`(): Unit =
        WorkerFixtures.runBounded {
            val rig = failingRig()
            rig.store.enqueue(1L, JobFixtures.audio(1))

            assertTrue("whisper-server: runOnce must report that it handled a job", rig.worker.runOnce())

            assertEquals(
                "whisper-server: one first call and three retries make four forwards",
                listOf(1, 2, 3, 4),
                rig.forwarder.calls.map { call -> call.attempt },
            )
            for (call in rig.forwarder.calls) {
                assertEquals("whisper-server: every call is for the one job", 1L, call.jobId)
                assertArrayEquals(
                    "whisper-server: every retry carries the same audio",
                    JobFixtures.audio(1),
                    call.audio,
                )
            }
        }

    @Test
    fun `the pauses are exactly three of ten seconds and none follows the last attempt`(): Unit =
        WorkerFixtures.runBounded {
            val rig = failingRig()
            rig.store.enqueue(1L, JobFixtures.audio(1))

            rig.worker.runOnce()

            assertEquals(
                "whisper-server: three pauses of ten seconds between four attempts",
                listOf(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10)),
                rig.pause.durations,
            )
            assertEquals(
                "whisper-server: each pause comes after attempt n and before attempt n + 1",
                listOf(1, 2, 3),
                rig.pause.callsAtPause,
            )
        }

    @Test
    fun `after the fourth failure the job is failed with the reason and its audio is gone`(): Unit =
        WorkerFixtures.runBounded {
            val rig = failingRig()
            rig.store.enqueue(1L, JobFixtures.audio(1))

            rig.worker.runOnce()

            val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
            assertEquals("whisper-server: the job must end failed", "failed", row.status)
            assertEquals("whisper-server: the attempts column must count all four calls", 4L, row.attempts)
            assertEquals(
                "whisper-server: the stored error must name the attempts and the reason",
                "failed after 4 attempts: boom",
                row.error,
            )
            assertEquals("whisper-server: a failed job has no audio", false, row.hasAudio)
            assertNull("whisper-server: a failed job has no result", row.result)
        }

    @Test
    fun `two failures and then a success finish the job done after three calls and two pauses`(): Unit =
        WorkerFixtures.runBounded {
            val rig = WorkerFixtures.rig(
                openTemp(),
                script = listOf(
                    WorkerFixtures.failure("one"),
                    WorkerFixtures.failure("two"),
                    WorkerFixtures.success("third-result"),
                ),
            )
            rig.store.enqueue(1L, JobFixtures.audio(1))

            assertTrue("whisper-server: runOnce must report that it handled a job", rig.worker.runOnce())

            val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
            assertEquals("whisper-server: the job must end done", "done", row.status)
            assertEquals("whisper-server: the attempts column must count three calls", 3L, row.attempts)
            assertEquals("whisper-server: three forwards expected", 3, rig.forwarder.calls.size)
            assertEquals(
                "whisper-server: two pauses of ten seconds expected",
                listOf(Duration.ofSeconds(10), Duration.ofSeconds(10)),
                rig.pause.durations,
            )
            assertNull("whisper-server: a done job has no error", row.error)
            assertEquals("whisper-server: a done job has no audio", false, row.hasAudio)
        }

    @Test
    fun `the result of the call that finally succeeded is the one stored`(): Unit = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(
            openTemp(),
            script = listOf(WorkerFixtures.failure("one"), WorkerFixtures.success("late-result")),
        )
        rig.store.enqueue(1L, JobFixtures.audio(1))

        rig.worker.runOnce()

        assertEquals(
            "whisper-server: the stored result must be the one the second call returned",
            "late-result",
            WorkerFixtures.rawJob(rig.temp.db, 1L).result,
        )
    }

    @Test
    fun `a first call that succeeds causes no pause at all`(): Unit = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp(), script = listOf(WorkerFixtures.success("quick-result")))
        rig.store.enqueue(1L, JobFixtures.audio(1))

        rig.worker.runOnce()

        assertEquals("whisper-server: a success must not wait", emptyList<Duration>(), rig.pause.durations)
        assertEquals("whisper-server: one forward expected", 1, rig.forwarder.calls.size)
        val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
        assertEquals("whisper-server: the job must end done", "done", row.status)
        assertEquals("whisper-server: one attempt only", 1L, row.attempts)
    }

    @Test
    fun `while a job waits for its retry it is processing and the next job has not started`(): Unit =
        WorkerFixtures.runBounded {
            val rig = failingRig(watched = listOf(1L, 2L))
            WorkerFixtures.enqueueJobs(rig.store, 2)

            rig.worker.runOnce()

            val expected = listOf(JobStatus.PROCESSING, JobStatus.QUEUED)
            assertEquals(
                "whisper-server: at each of the three pauses job 1 is processing and job 2 is queued",
                listOf(expected, expected, expected),
                rig.pause.statusesAtPause,
            )
            assertEquals(
                "whisper-server: the next job must not have been touched",
                0L,
                WorkerFixtures.rawJob(rig.temp.db, 2L).attempts,
            )
        }

    @Test
    fun `the job after a job that fails four times is not forwarded before the fourth attempt returned`(): Unit =
        WorkerFixtures.runBounded {
            val rig = WorkerFixtures.rig(
                openTemp(),
                script = listOf(
                    WorkerFixtures.success("first-result"),
                    WorkerFixtures.failure("x"),
                    WorkerFixtures.failure("x"),
                    WorkerFixtures.failure("x"),
                    WorkerFixtures.failure("x"),
                ),
            )
            val ids = WorkerFixtures.enqueueJobs(rig.store, 5)

            var handled = 0
            while (rig.worker.runOnce()) {
                handled += 1
            }

            assertEquals("whisper-server: all five jobs are handled", 5, handled)
            assertEquals(
                "whisper-server: job 2 uses all four attempts before job 3 starts",
                listOf(
                    Pair(1L, 1),
                    Pair(2L, 1),
                    Pair(2L, 2),
                    Pair(2L, 3),
                    Pair(2L, 4),
                    Pair(3L, 1),
                    Pair(4L, 1),
                    Pair(5L, 1),
                ),
                rig.forwarder.pairs(),
            )
            val states = ArrayList<String>()
            for (id in ids) {
                states.add(WorkerFixtures.rawJob(rig.temp.db, id).status)
            }
            assertEquals(
                "whisper-server: only job 2 fails",
                listOf("done", "failed", "done", "done", "done"),
                states,
            )
        }
}
