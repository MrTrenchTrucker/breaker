package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The failure counter of `SyncWorker` keeps its own course when one failure carries a Retry-After: the wait
 * of that failure is the longer value, and the next failure still takes the next backoff step. The fakes live
 * in `SyncWorkerFakes.kt`.
 */
class SyncWorkerRetryAfterStepsTest {

    @Test
    fun `a Retry-After on the third failure does not restart the backoff steps`() {
        val service = FakeSyncService().apply {
            reports += SyncReport(0, 1)
            reports += SyncReport(0, 1)
            reports += SyncReport(0, 1, retryAfterSeconds = 300)
            reports += SyncReport(0, 1)
        }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = makeWorker({ testSession("token-a") }, service, scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals("failure 1 is step 1, 30 s", listOf(30_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 30_000)
        drainOnce(service, 2)
        assertEquals("failure 2 is step 2, 1 min", listOf(60_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 60_000)
        drainOnce(service, 3)
        assertEquals("failure 3 waits for Retry-After 300 s, longer than step 3", listOf(300_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 300_000)
        drainOnce(service, 4)
        assertEquals("failure 4 is step 4, 4 min, not a restart at step 2", listOf(240_000L), scheduler.delays())
        assertEquals(4, service.callCount)
    }
}
