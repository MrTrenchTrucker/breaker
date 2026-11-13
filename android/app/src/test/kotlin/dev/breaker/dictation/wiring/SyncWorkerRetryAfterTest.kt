package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for how `SyncWorker` honours a 429's Retry-After: the wait after a failed drain is
 * the longer of the backoff step and Retry-After, the step counter keeps its own course, and a
 * new save still cancels the wait. The fakes live in `SyncWorkerFakes.kt`.
 */
class SyncWorkerRetryAfterTest {

    private class Case(val name: String, val stepMs: Long, val retryAfter: Int?, val expectedMs: Long)

    private fun worker(service: FakeSyncService, scheduler: FakeSyncScheduler): SyncWorker =
        makeWorker({ testSession("token-a") }, service, scheduler)

    @Test
    fun `maxDelayMs waits for the longer of the backoff step and Retry-After`() {
        val cases = listOf(
            Case("no Retry-After keeps the step", 30_000L, null, 30_000L),
            Case("Retry-After below the step keeps the step", 60_000L, 30, 60_000L),
            Case("equal values", 120_000L, 120, 120_000L),
            Case("Retry-After above the step wins", 240_000L, 300, 300_000L),
            Case("seconds are turned into milliseconds", 30_000L, 45, 45_000L),
            Case("the 15 min step is beaten by 3600 s", 900_000L, 3600, 3_600_000L),
            Case("zero Retry-After keeps the step", 30_000L, 0, 30_000L),
            Case("a huge Retry-After does not overflow", 30_000L, Int.MAX_VALUE, 2_147_483_647_000L),
        )
        for (case in cases) {
            assertEquals(case.name, case.expectedMs, maxDelayMs(case.stepMs, case.retryAfter))
        }
    }

    @Test
    fun `a partial drain with Retry-After 120 waits 120 s, not 30 s`() {
        // pushed > 0 resets the failure count, so the step is 30 s; Retry-After 120 s is longer.
        val service = FakeSyncService().apply { reports += SyncReport(1, 1, retryAfterSeconds = 120) }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service, scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals("Retry-After 120 s wins over the 30 s step", listOf(120_000L), scheduler.delays())
        assertEquals(1, service.callCount)
    }

    @Test
    fun `the failure after a Retry-After wait takes the next backoff step`() {
        val service = FakeSyncService().apply {
            reports += SyncReport(0, 1, retryAfterSeconds = 120)
            reports += SyncReport(0, 1)
        }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = worker(service, scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals("step 1 is 30 s, Retry-After 120 s is longer", listOf(120_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 120_000)
        drainOnce(service, 2)
        assertEquals("the second failure is step 2, 1 min", listOf(60_000L), scheduler.delays())
        assertEquals(2, service.callCount)
    }

    @Test
    fun `a new save cancels a Retry-After wait and runs one drain`() {
        val service = FakeSyncService().apply {
            reports += SyncReport(0, 1, retryAfterSeconds = 600)
            reports += SyncReport(1, 0)
        }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service, scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals(listOf(600_000L), scheduler.delays())
        val handle = scheduler.delayed.last().handle
        assertFalse("the wait is still armed", handle.cancelled)
        worker.onSave() // a new save during the wait: cancel it, run now
        assertTrue("the new save cancelled the wait", handle.cancelled)
        drainOnce(service, 2)
        assertEquals("a confirmed drain arms nothing", emptyList<Long>(), scheduler.delays())
        assertEquals(2, service.callCount)
    }
}
