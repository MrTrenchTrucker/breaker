package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for `SyncWorker`'s backoff rules: 30 s / 1 min / 2 min / 4 min capped at 15 min,
 * the reset on a confirmed drain, a new save and a token rotation, the zero-report outage
 * paths, a throwing pending count and a partial drain. The fakes live in `SyncWorkerFakes.kt`.
 */
class SyncWorkerBackoffTest {

    private var sessionToken = "token-a"

    private fun currentSessionGate(): AuthSession? = testSession(sessionToken)

    private fun worker(
        gate: () -> AuthSession? = ::currentSessionGate,
        service: FakeSyncService = FakeSyncService(),
        scheduler: FakeSyncScheduler = FakeSyncScheduler(),
        background: SyncFakeBackground = SyncFakeBackground(),
        pendingCount: () -> Int = { 0 },
    ): SyncWorker = makeWorker(gate, service, scheduler, background, pendingCount)


    @Test
    fun `a FAILED drain schedules the next run at 30 s, then 1 min, then 2 min, then 4 min`() {
        val service = FakeSyncService().apply { reports += List(4) { SyncReport(0, 1) } }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave() // call 1 fails at t = 0
        drainOnce(service, 1)
        assertEquals(listOf(30_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 30_000) // call 2, at t = 30 s
        drainOnce(service, 2)
        assertEquals(listOf(60_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 60_000) // call 3, at t = 90 s
        drainOnce(service, 3)
        assertEquals(listOf(120_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 120_000) // call 4, at t = 210 s
        drainOnce(service, 4)
        assertEquals(listOf(240_000L), scheduler.delays())
        assertEquals(4, service.callCount)
        assertEquals(210_000L, clock.nowMillis)
    }

    @Test
    fun `the backoff is capped at 15 min`() {
        val service = FakeSyncService().apply { reports += List(6) { SyncReport(0, 1) } }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave()
        drainOnce(service, 1)
        advanceAndFire(clock, scheduler, 30_000)
        drainOnce(service, 2)
        advanceAndFire(clock, scheduler, 60_000)
        drainOnce(service, 3)
        advanceAndFire(clock, scheduler, 120_000)
        drainOnce(service, 4)
        advanceAndFire(clock, scheduler, 240_000) // call 5
        drainOnce(service, 5)
        assertEquals(listOf(900_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 900_000) // call 6
        drainOnce(service, 6)
        assertEquals(listOf(900_000L), scheduler.delays())
        assertEquals(6, service.callCount)
    }

    @Test
    fun `a CONFIRMED drain resets the backoff to 30 s`() {
        val service = FakeSyncService().apply {
            reports += SyncReport(0, 1) // call 1 fails -> 30 s armed
            reports += SyncReport(1, 0) // call 2 is confirmed -> backoff reset, nothing armed
            reports += SyncReport(0, 1) // call 3 fails -> 30 s again, not 60 s
        }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals("a failed drain arms the next run", listOf(30_000L), scheduler.delays())
        // The confirmed drain has to be reached by the armed timer, not by a new save: a new
        // save would reset the backoff on its own and hide a broken confirmed-drain branch.
        scheduler.firePending()
        drainOnce(service, 2)
        assertEquals("a confirmed drain arms nothing", 0, scheduler.delayed.size)
        worker.onSave() // the next save starts a fresh run, and its failure starts at 30 s
        drainOnce(service, 3)
        assertEquals("the confirmed drain reset the count", listOf(30_000L), scheduler.delays())
        assertEquals(3, service.callCount)
    }

    @Test
    fun `a new save resets the backoff to 30 s`() {
        val service = FakeSyncService().apply { reports += List(3) { SyncReport(0, 1) } }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave() // call 1 fails -> pending 30 s
        drainOnce(service, 1)
        advanceAndFire(clock, scheduler, 30_000) // call 2 fails -> pending 60 s
        drainOnce(service, 2)
        val handle = scheduler.delayed.last().handle
        worker.onSave() // new save: cancels the armed handle and runs the drain at once
        assertTrue("the new save cancelled the armed handle", handle.cancelled)
        drainOnce(service, 3) // the save's drain started now, not on the old timer
        assertEquals("the reset backoff arms 30 s", listOf(30_000L), scheduler.delays())
        assertEquals(3, service.callCount)
    }

    @Test
    fun `a different session token resets the backoff to 30 s`() {
        val service = FakeSyncService().apply { reports += List(2) { SyncReport(0, 1) } }
        val scheduler = FakeSyncScheduler()
        val worker = worker(gate = { currentSessionGate() }, service = service, scheduler = scheduler)
        worker.onSave() // call 1 fails under token-a
        drainOnce(service, 1)
        assertEquals(listOf(30_000L), scheduler.delays())
        sessionToken = "token-b" // session rotated while a run is pending
        worker.onSave() // token differs: cancels the armed handle and runs the drain at once
        drainOnce(service, 2) // call 2 fails under token-b, started now
        assertEquals("a token rotation restarts at 30 s", listOf(30_000L), scheduler.delays())
        assertEquals(2, service.callCount)
    }

    @Test
    fun `a 401 FAILED drain does not re-run while the clock stands still`() {
        val service = FakeSyncService().apply { reports += SyncReport(0, 1) }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals("the first backoff is 30 s, never immediate", 30_000L, scheduler.delayed.last().delayMs)
        assertEquals(1, service.callCount)
        // the clock never advances and the pending delayed run is never fired: no second call, ever
        Thread.sleep(200)
        assertEquals(1, service.callCount)
        assertEquals(0, clock.nowMillis)
    }

    @Test
    fun `a zero report during an outage advances the backoff like a failed drain`() {
        val service = FakeSyncService().apply { reports += listOf(SyncReport(0, 1), SyncReport(0, 0)) }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = worker(service = service, scheduler = scheduler, pendingCount = { 3 })
        worker.onSave() // call 1 fails -> armed at 30 s
        drainOnce(service, 1)
        assertEquals(listOf(30_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 30_000) // call 2 returns (0,0) with 3 rows still queued
        drainOnce(service, 2)
        assertEquals("an unreachable outage counts as a failure and arms 1 min", listOf(60_000L), scheduler.delays())
        assertEquals(2, service.callCount)
    }

    @Test
    fun `a zero report with pending rows arms 30 s, then 1 min on the next zero report`() {
        val service = FakeSyncService().apply { reports += listOf(SyncReport(0, 0), SyncReport(0, 0)) }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = worker(service = service, scheduler = scheduler, pendingCount = { 3 })
        worker.onSave() // call 1: (0,0) with 3 pending -> unreachable, arm 30 s
        drainOnce(service, 1)
        assertEquals("the first outage arms 30 s", listOf(30_000L), scheduler.delays())
        advanceAndFire(clock, scheduler, 30_000) // call 2: (0,0) with 3 pending -> arm 1 min
        drainOnce(service, 2)
        assertEquals("the next outage arms 1 min", listOf(60_000L), scheduler.delays())
        assertEquals(2, service.callCount)
    }

    @Test
    fun `a zero report with an empty queue arms nothing and stays idle`() {
        val service = FakeSyncService().apply { reports += SyncReport(0, 0) }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service = service, scheduler = scheduler, pendingCount = { 0 })
        worker.onSave() // call 1 returns (0,0) and nothing is queued
        drainOnce(service, 1)
        assertEquals("an empty queue arms nothing", 0, scheduler.delayed.size)
        assertEquals("no runSoon either", 0, scheduler.soon.size)
        assertEquals(1, service.callCount)
    }

    @Test
    fun `a throwing pendingCount arms the backoff like a failure`() {
        val service = FakeSyncService().apply { reports += SyncReport(0, 0) }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service = service, scheduler = scheduler,
            pendingCount = { throw RuntimeException("queue database gone") })
        worker.onSave() // call 1 returns (0,0) and pendingCount throws
        drainOnce(service, 1)
        assertEquals("a throwing count is treated as pending and arms 30 s", listOf(30_000L), scheduler.delays())
        assertEquals(1, service.callCount)
    }

    @Test
    fun `a partial drain pushed 2 failed 1 arms 30 s`() {
        val service = FakeSyncService().apply { reports += SyncReport(2, 1) }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service = service, scheduler = scheduler, pendingCount = { 0 })
        worker.onSave()
        drainOnce(service, 1)
        assertEquals("a partial drain backs off at 30 s", listOf(30_000L), scheduler.delays())
        assertEquals(1, service.callCount)
    }

    @Test
    fun `a new save during a backoff cancels the armed handle and runs one drain`() {
        val service = FakeSyncService().apply {
            reports += List(4) { SyncReport(0, 1) } // calls 1-4 fail
            reports += SyncReport(1, 0)             // call 5: the save's drain confirms
        }
        val scheduler = FakeSyncScheduler()
        val clock = ManualClock()
        val worker = worker(service = service, scheduler = scheduler, pendingCount = { 0 })
        worker.onSave() // call 1 fails -> pending 30 s
        drainOnce(service, 1)
        advanceAndFire(clock, scheduler, 30_000) // call 2 fails -> pending 60 s
        drainOnce(service, 2)
        advanceAndFire(clock, scheduler, 60_000) // call 3 fails -> pending 2 min
        drainOnce(service, 3)
        advanceAndFire(clock, scheduler, 120_000) // call 4 fails -> pending 4 min
        drainOnce(service, 4)
        assertEquals("four failures arm 4 min", listOf(240_000L), scheduler.delays())
        val handle = scheduler.delayed.last().handle
        assertFalse("the handle is still armed", handle.cancelled)
        worker.onSave() // new save during the backoff: cancel the handle, run now
        assertTrue("the save cancelled the armed handle", handle.cancelled)
        drainOnce(service, 5) // exactly one drain started for the save
        assertEquals("nothing re-armed after the confirmed drain", emptyList<Long>(), scheduler.delays())
        assertEquals(5, service.callCount)
    }

    @Test
    fun `a confirmed drain reached by the timer resets failures so the next timer failure arms 30 s not 120 s`() {
        // M17: line 127 must reset failures=0 on the confirmed-drain branch. The third failure is
        // driven by the armed timer (onStart, same token), never by a new save — a new save resets
        // failures at trigger (line 80) and would hide a broken confirmed-drain branch.
        val service = FakeSyncService().apply {
            reports += SyncReport(0, 1)   // call 1 fails -> failures = 1, arm 30 s
            reports += SyncReport(0, 1)   // call 2 fails -> failures = 2, arm 60 s
            reports += SyncReport(1, 0)   // call 3 confirmed -> failures reset to 0 (line 127)
            reports += SyncReport(0, 1)   // call 4 fails -> delayFor(1) = 30 s (real) vs delayFor(3) = 120 s (M17)
        }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals(listOf(30_000L), scheduler.delays())
        scheduler.firePending()          // call 2 fails
        drainOnce(service, 2)
        assertEquals(listOf(60_000L), scheduler.delays())
        scheduler.firePending()          // call 3 confirmed -> arms nothing
        drainOnce(service, 3)
        assertEquals("a confirmed drain arms nothing", 0, scheduler.delayed.size)
        worker.onStart()                 // same token, no new save: does NOT reset failures (line 80 skipped)
        drainOnce(service, 4)
        assertEquals("after the confirmed reset, the next failure arms 30 s", listOf(30_000L), scheduler.delays())
        assertEquals(4, service.callCount)
    }

    @Test
    fun `a token rotation on plain onStart resets the backoff and runs now`() {
        // M19: the token-change arm at line 79 must fire on newSave=false too.
        val service = FakeSyncService().apply {
            reports += SyncReport(0, 1)   // call 1 fails under token-a -> arm 30 s
            reports += SyncReport(0, 1)   // call 2 fails under token-a -> arm 60 s
            reports += SyncReport(0, 1)   // call 3 fails under token-b -> arm 30 s again
        }
        val scheduler = FakeSyncScheduler()
        val worker = worker(gate = { currentSessionGate() }, service = service, scheduler = scheduler)
        worker.onSave()                   // call 1 under token-a
        drainOnce(service, 1)
        assertEquals(listOf(30_000L), scheduler.delays())
        scheduler.firePending()           // call 2 under token-a
        drainOnce(service, 2)
        assertEquals(listOf(60_000L), scheduler.delays())
        sessionToken = "token-b"          // session rotated while the 60 s re-arm is pending
        worker.onStart()                  // newSave=false, but token differs: reset, cancel, run now
        drainOnce(service, 3)
        assertEquals("a token rotation restarts at 30 s", listOf(30_000L), scheduler.delays())
        assertEquals(3, service.callCount)
    }
}
