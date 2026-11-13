package dev.breaker.dictation.stt.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * When the work finishes first, the watchdog still owns a deadline timer that has not fired. The scope
 * the watchdog made is cancelled on the way out, so that timer is cancelled too and is not left waiting.
 */
class CallWatchdogCleanupTest {
    @Test
    fun `a call that finishes cancels the deadline timer it started`() {
        var timerCancelled = false
        val timer = object : DeadlineTimer {
            override suspend fun await(ms: Long) {
                try {
                    awaitCancellation()
                } finally {
                    timerCancelled = true
                }
            }
        }
        val watchdog = CallWatchdog(timer, Dispatchers.Unconfined)
        val outcome = runBlocking { watchdog.run(5_000L, AbortHandle()) { 42 } }
        val finished = outcome as? WatchdogOutcome.Finished<*>
        assertEquals("the work finished first with its value", 42, finished?.value)
        assertTrue("the deadline timer was left running after the call ended", timerCancelled)
    }
}
