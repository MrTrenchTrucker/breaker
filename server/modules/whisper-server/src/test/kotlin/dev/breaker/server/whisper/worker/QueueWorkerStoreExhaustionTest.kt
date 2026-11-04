package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.jobs.JobFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a store call that never recovers must not hold the worker's single slot forever. The
 * retry budget is bounded to [MAX_STORE_ATTEMPTS] (10); after that the call is given up, the job
 * (if any) is failed with a plain reason, and the slot is freed. These tests put an always-throwing
 * [FlakyJobStore] in the seam: the same store that the unwrapped worker loops on forever, the
 * wrapped worker gives up after exactly 10 attempts.
 */
internal class QueueWorkerStoreExhaustionTest : dev.breaker.server.whisper.db.TempDatabaseTest() {

    private fun workerOver(rig: WorkerRig, fake: FlakyJobStore): QueueWorker =
        QueueWorker(fake, rig.forwarder, rig.pause)

    @Test
    fun `a claimNext that never recovers gives up after ten attempts and frees the slot`() = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp())
        rig.store.enqueue(1L, JobFixtures.audio(1))
        val fake = FlakyJobStore(rig.store).apply { claimThrows = Int.MAX_VALUE }
        val worker = workerOver(rig, fake)

        val handled = worker.runOnce()

        assertTrue("whisper-server: runOnce reports it handled the (exhausted) claim", handled)
        assertEquals("whisper-server: the claim seam is attempted exactly ten times", 10, fake.throws("claimNext"))
        assertEquals("whisper-server: the job is never claimed, it stays queued", "queued", WorkerFixtures.rawJob(rig.temp.db, 1L).status)
        assertEquals("whisper-server: the forwarder is never called", 0, rig.forwarder.calls.size)
        // The slot is free: a second runOnce can still drain the queue (it will retry and give up
        // again, but it returns rather than looping forever).
        assertTrue("whisper-server: the slot is free, a second runOnce returns", worker.runOnce())
    }

    @Test
    fun `a complete that never recovers fails the job with a plain reason and frees the slot`() = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(openTemp())
        rig.store.enqueue(1L, JobFixtures.audio(1))
        val fake = FlakyJobStore(rig.store).apply { completeThrows = Int.MAX_VALUE }
        val worker = workerOver(rig, fake)

        val handled = worker.runOnce()

        assertTrue("whisper-server: runOnce reports it handled the (exhausted) job", handled)
        val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
        assertEquals("whisper-server: the job ends failed", "failed", row.status)
        // The reason is plain: a store exception message can carry a path or a key, so it is never copied in.
        assertEquals(
            "whisper-server: the failed reason names the attempts, not the store exception",
            "store call failed after 10 attempts",
            row.error,
        )
        assertEquals("whisper-server: the forwarder ran once (the success path reached complete)", 1, rig.forwarder.calls.size)
        assertEquals("whisper-server: complete is attempted exactly ten times", 10, fake.throws("complete"))
    }
}
