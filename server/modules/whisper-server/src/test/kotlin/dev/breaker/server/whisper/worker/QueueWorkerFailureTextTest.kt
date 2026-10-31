package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.jobs.JobFixtures
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class QueueWorkerFailureTextTest : TempDatabaseTest() {

    private val prefix = "failed after 4 attempts: "

    /** Runs one job whose every call is [step] and returns the error text stored for it. */
    private suspend fun storedErrorWhenEveryCall(step: ForwardStep): String? {
        val rig = WorkerFixtures.rig(openTemp(), fallback = step)
        rig.store.enqueue(1L, JobFixtures.audio(1))
        rig.worker.runOnce()
        return WorkerFixtures.rawJob(rig.temp.db, 1L).error
    }

    @Test
    fun `a thrown exception is stored by class name only`(): Unit = WorkerFixtures.runBounded {
        val stored = storedErrorWhenEveryCall(
            WorkerFixtures.throwing { IllegalStateException("http://secret-host.invalid/?key=SECRET-KEY-123") },
        )

        assertEquals(
            "whisper-server: only the exception class may reach the stored error",
            prefix + "the transcription service call failed (IllegalStateException)",
            stored,
        )
        assertFalse("whisper-server: the host in the message leaked", stored!!.contains("secret-host"))
        assertFalse("whisper-server: the key in the message leaked", stored.contains("SECRET-KEY-123"))
    }

    @Test
    fun `a thrown IOException is stored by class name only`(): Unit = WorkerFixtures.runBounded {
        val stored = storedErrorWhenEveryCall(
            WorkerFixtures.throwing { IOException("connect to http://secret-host.invalid failed, key SECRET-KEY-123") },
        )

        assertEquals(
            "whisper-server: only the exception class may reach the stored error",
            prefix + "the transcription service call failed (IOException)",
            stored,
        )
        assertFalse("whisper-server: the host in the message leaked", stored!!.contains("secret-host"))
        assertFalse("whisper-server: the key in the message leaked", stored.contains("SECRET-KEY-123"))
    }

    @Test
    fun `a reason longer than five hundred characters is cut to its first five hundred`(): Unit =
        WorkerFixtures.runBounded {
            val reason = "0123456789".repeat(60)

            val stored = storedErrorWhenEveryCall(WorkerFixtures.failure(reason))

            // Re-pointed: the whole stored text is bounded at 500, prefix kept intact
            assertEquals(
                "whisper-server: the stored text must be bounded at 500",
                true,
                stored!!.length <= 500,
            )
            assertEquals(
                "whisper-server: the prefix must be kept intact",
                true,
                stored.startsWith(prefix),
            )
            assertEquals(
                "whisper-server: the reason is cut to 475 characters",
                prefix + reason.substring(0, 475),
                stored,
            )
        }

    @Test
    fun `a reason of exactly five hundred characters is stored whole`(): Unit = WorkerFixtures.runBounded {
        val reason = "abcdefghij".repeat(50)

        val stored = storedErrorWhenEveryCall(WorkerFixtures.failure(reason))

        // Re-pointed: the whole stored text is bounded at 500, prefix kept intact
        assertEquals(
            "whisper-server: the stored text must be bounded at 500",
            true,
            stored!!.length <= 500,
        )
        assertEquals(
            "whisper-server: the prefix must be kept intact",
            true,
            stored.startsWith(prefix),
        )
        assertEquals(
            "whisper-server: the reason is cut to 475 characters",
            prefix + reason.substring(0, 475),
            stored,
        )
    }

    @Test
    fun `a blank reason is replaced by a plain sentence`(): Unit = WorkerFixtures.runBounded {
        val stored = storedErrorWhenEveryCall(WorkerFixtures.failure("   "))

        assertEquals(
            "whisper-server: a blank reason must not be stored as blank",
            prefix + "the transcription service reported a failure",
            stored,
        )
    }

    @Test
    fun `the text of the last attempt is the one that is stored`(): Unit = WorkerFixtures.runBounded {
        val rig = WorkerFixtures.rig(
            openTemp(),
            script = listOf(
                WorkerFixtures.failure("one"),
                WorkerFixtures.failure("one"),
                WorkerFixtures.failure("one"),
                WorkerFixtures.failure("four"),
            ),
        )
        rig.store.enqueue(1L, JobFixtures.audio(1))

        rig.worker.runOnce()

        assertEquals(
            "whisper-server: the stored text must come from the fourth attempt",
            prefix + "four",
            WorkerFixtures.rawJob(rig.temp.db, 1L).error,
        )
    }

    @Test
    fun `a stray cancellation from inside the forwarder is a failed attempt not a shutdown`(): Unit =
        WorkerFixtures.runBounded {
            val rig = WorkerFixtures.rig(
                openTemp(),
                fallback = WorkerFixtures.throwing { CancellationException("stray") },
            )
            rig.store.enqueue(1L, JobFixtures.audio(1))

            val handled = rig.worker.runOnce()

            assertTrue("whisper-server: runOnce must return normally and report the job", handled)
            assertEquals("whisper-server: four calls expected", 4, rig.forwarder.calls.size)
            assertEquals(
                "whisper-server: three pauses expected",
                listOf(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10)),
                rig.pause.durations,
            )
            val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
            assertEquals("whisper-server: the job must end failed", "failed", row.status)
            assertEquals(
                "whisper-server: the class name is stored and the message is not",
                prefix + "the transcription service call failed (CancellationException)",
                row.error,
            )
        }

    @Test
    fun `cancelling the coroutine that runs the job propagates and leaves the job processing`(): Unit =
        WorkerFixtures.runBounded {
            val entered = CompletableDeferred<Unit>()
            val rig = WorkerFixtures.rig(openTemp(), script = listOf(WorkerFixtures.suspendForever(entered)))
            rig.store.enqueue(1L, JobFixtures.audio(1))
            val running = launch { rig.worker.runOnce() }

            entered.await()
            running.cancelAndJoin()

            val row = WorkerFixtures.rawJob(rig.temp.db, 1L)
            assertEquals("whisper-server: a cancelled job must stay processing", "processing", row.status)
            assertEquals("whisper-server: no second attempt was counted", 1L, row.attempts)
            assertNull("whisper-server: no error may be stored by a cancel", row.error)
            assertEquals("whisper-server: the forwarder was called once", 1, rig.forwarder.calls.size)
            assertEquals(
                "whisper-server: a cancel must not be treated as a failed attempt that waits to retry",
                emptyList<Duration>(),
                rig.pause.durations,
            )
        }
}
