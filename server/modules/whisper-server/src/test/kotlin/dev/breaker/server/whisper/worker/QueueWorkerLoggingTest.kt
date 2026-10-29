package dev.breaker.server.whisper.worker

import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobStoreOps
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker logs a store failure with the op, the job id and the exception class name only
 * (QueueWorker.logStoreFailure). A store message can carry a path or a key, so the log line must
 * never carry the message: the fake store throws an exception whose own message is a secret, and
 * the test proves that secret never reaches the log, nor the audio or the result of the job.
 */
internal class QueueWorkerLoggingTest : TempDatabaseTest() {

    // StoreAccessException is final, so this is a plain RuntimeException carrying the secret; the
    // worker catches Exception and logs only its class name, still "SecretStoreAccessException".
    /** A store exception whose own message is a secret the log must never carry. */
    private class SecretStoreAccessException : RuntimeException("SECRET-PATH-KEY should never be logged")

    /** Throws [SecretStoreAccessException] once on complete(), then delegates for real. */
    private class ThrowingOnceStore(private val delegate: JobStoreOps) : JobStoreOps by delegate {
        private var completeThrows = 0

        override suspend fun complete(jobId: Long, result: String) {
            if (completeThrows < 1) {
                completeThrows += 1
                throw SecretStoreAccessException()
            }
            delegate.complete(jobId, result)
        }
    }

    /** Collects every record the worker publishes on its logger. */
    private class CapturingHandler : Handler() {
        val records = ArrayList<LogRecord>()

        init {
            level = Level.ALL
        }

        override fun publish(record: LogRecord) {
            records.add(record)
        }

        override fun flush() {}

        override fun close() {}
    }

    @Test
    fun `a store failure logs the op, the job id and the class name, never the message or the payload`() = WorkerFixtures.runBounded {
            val rig = WorkerFixtures.rig(openTemp(), fallback = WorkerFixtures.success(JobFixtures.MARKER_RESULT))
            rig.store.enqueue(1L, JobFixtures.MARKER_AUDIO)
            val worker = QueueWorker(ThrowingOnceStore(rig.store), rig.forwarder, rig.pause)

            val logger = Logger.getLogger("dev.breaker.server.whisper.worker.QueueWorker")
            val handler = CapturingHandler()
            val savedLevel = logger.level
            val savedParentHandlers = logger.useParentHandlers
            try {
                logger.addHandler(handler)
                logger.level = Level.ALL
                logger.useParentHandlers = false
                try {
                    worker.runOnce()
                } catch (_: Exception) {
                    // A RED (unwrapped) worker would let the store throw escape; catching it keeps
                    // the test failing on the missing log line below instead of on the throw itself.
                }
            } finally {
                logger.removeHandler(handler)
                logger.level = savedLevel
                logger.useParentHandlers = savedParentHandlers
            }

            val logText = handler.records.joinToString("\n") { record -> record.message ?: "" }
            assertTrue(
                "whisper-server: the store failure must publish a log line: $logText",
                handler.records.isNotEmpty(),
            )
            assertTrue("whisper-server: the log must name the op: $logText", logText.contains("complete"))
            assertTrue("whisper-server: the log must name the job id: $logText", logText.contains("job 1"))
            assertTrue(
                "whisper-server: the log must name the exception class: $logText",
                logText.contains("SecretStoreAccessException"),
            )
            assertFalse(
                "whisper-server: the exception message must never be logged: $logText",
                logText.contains("SECRET-PATH-KEY should never be logged"),
            )
            assertFalse(
                "whisper-server: the audio must never be logged: $logText",
                logText.contains(String(JobFixtures.MARKER_PROBE, Charsets.ISO_8859_1)),
            )
            assertFalse(
                "whisper-server: the result must never be logged: $logText",
                logText.contains(JobFixtures.MARKER_RESULT),
            )
    }
}
