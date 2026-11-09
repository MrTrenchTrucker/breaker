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
 * JVM tests for `SyncWorker`'s trigger and drain rules: one drain per trigger, coalescing,
 * the no-session case, the caller's thread, and `stop()`. The fakes and the clock helpers
 * live in `SyncWorkerFakes.kt`; the backoff rules are in `SyncWorkerBackoffTest`.
 */
class SyncWorkerTest {

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
    fun `trigger runs one drain and one only`() {
        val service = FakeSyncService()
        val scheduler = FakeSyncScheduler()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals("one drain only", 1, service.callCount)
        assertEquals("no delayed run armed", 0, scheduler.delayed.size)
    }

    @Test
    fun `ten triggers coalesce into one pending run`() {
        val service = FakeSyncService()
        val worker = worker(service = service)
        worker.onSave()
        service.awaitEntered(1) // the first drain is in flight and blocked
        repeat(9) { worker.onSave() } // coalesced while a drain runs
        drainOnce(service, 1)
        assertEquals("ten saves coalesce into one drain", 1, service.callCount)
    }

    @Test
    fun `no session pushPending is never called`() {
        val service = FakeSyncService()
        val scheduler = FakeSyncScheduler()
        val worker = worker(gate = { null }, service = service, scheduler = scheduler)
        worker.onSave()
        worker.onStart()
        Thread.sleep(100)
        assertEquals("no pushPending without a session", 0, service.callCount)
        assertEquals("no scheduler use without a session", 0, scheduler.soon.size + scheduler.delayed.size)
    }

    @Test
    fun `a session present pushPending is called`() {
        val service = FakeSyncService()
        val worker = worker(service = service)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals(1, service.callCount)
    }

    @Test
    fun `pushPending is never called on the caller's thread`() {
        // Fail fast: a non-blocking service returns at once, so an inline run cannot hang the
        // suite waiting for a release that never comes. The thread is recorded and asserted.
        val service = FakeSyncService(blocking = false)
        val background = SyncFakeBackground()
        val caller = Thread.currentThread()
        val worker = worker(service = service, background = background)
        worker.onSave()
        waitFor("the drain to start") { service.callCount == 1 }
        val ran = service.threadOf(1)
        assertNotSame("pushPending ran on the caller's thread", caller, ran)
        assertEquals("pushPending ran on the background thread", "test-bg", ran?.name)
    }

    @Test
    fun `stop() stops further triggers and cancels the pending delayed run`() {
        val service = FakeSyncService().apply { reports += SyncReport(0, 1) }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals(1, scheduler.delayed.size)
        val handle = scheduler.delayed.last().handle
        worker.stop()
        assertTrue("stop() cancelled the pending delayed run", handle.cancelled)
        worker.onSave() // ignored: stopped
        scheduler.firePending() // a cancelled handle fires anyway: must not start a drain
        Thread.sleep(100)
        assertEquals(1, service.callCount)
    }

    @Test
    fun `no session at trigger (onSave) submits no background run`() {
        // M13 / M26: the gate short-circuits at trigger (line 76), so startRun never submits.
        val service = FakeSyncService()
        val scheduler = FakeSyncScheduler()
        val background = SyncFakeBackground()
        val worker = worker(gate = { null }, service = service, scheduler = scheduler, background = background)
        worker.onSave()
        assertEquals("no pushPending without a session", 0, service.callCount)
        assertEquals("no background thread submitted without a session", 0, background.threads.size)
        assertEquals("no delayed run armed without a session", 0, scheduler.delayed.size)
    }

    @Test
    fun `no session at trigger (onStart) submits no background run`() {
        // M13 / M26: same gate short-circuit on the app-start trigger.
        val service = FakeSyncService()
        val scheduler = FakeSyncScheduler()
        val background = SyncFakeBackground()
        val worker = worker(gate = { null }, service = service, scheduler = scheduler, background = background)
        worker.onStart()
        assertEquals("no pushPending without a session", 0, service.callCount)
        assertEquals("no background thread submitted without a session", 0, background.threads.size)
        assertEquals("no delayed run armed without a session", 0, scheduler.delayed.size)
    }

    @Test
    fun `session present at trigger but gone at run time never calls pushPending`() {
        // M24: runOnce re-reads the gate; a session at trigger is not enough.
        val service = FakeSyncService()
        val scheduler = FakeSyncScheduler()
        var sessionThenNull = true
        val gate: () -> AuthSession? = {
            val present = sessionThenNull
            sessionThenNull = false
            if (present) testSession("token-a") else null
        }
        val worker = worker(gate = gate, service = service, scheduler = scheduler)
        worker.onSave()
        Thread.sleep(200)
        assertEquals("the gate is re-read before the drain runs: pushPending is never called", 0, service.callCount)
        assertEquals("no delayed run armed either", 0, scheduler.delayed.size)
    }

    @Test
    fun `nine triggers while a backoff is armed still run only one drain`() {
        // M14 / M25: the armed timer owns the next run; triggers are coalesced.
        val service = FakeSyncService().apply { reports += SyncReport(0, 1) }
        val scheduler = FakeSyncScheduler()
        val worker = worker(service = service, scheduler = scheduler)
        worker.onSave()
        drainOnce(service, 1)
        assertEquals("a failed drain arms the next run", listOf(30_000L), scheduler.delays())
        repeat(9) { worker.onStart() } // same token, no new save
        Thread.sleep(200)
        assertEquals("coalesced by the armed backoff: one drain only", 1, service.callCount)
    }
}
