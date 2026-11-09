package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.dictation.core.port.SyncService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A session built, never subclassed: `AuthSession` is a final data class. */
internal fun testSession(token: String): AuthSession = AuthSession(
    userId = "u",
    username = "u",
    token = token,
    role = UserRole.USER,
    scopes = emptySet(),
)

internal fun waitFor(label: String, condition: () -> Boolean) {
    for (i in 1..500) {
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("app: $label did not happen")
}

/** One pushPending call the test drives: entered when the call starts, released when it may return. */
internal class Gate {
    val entered = CountDownLatch(1)
    val released = CountDownLatch(1)
    val finished = CountDownLatch(1)
    var thread: Thread? = null
}

internal class FakeSyncService(private val blocking: Boolean = true) : SyncService {
    private val gates = ConcurrentLinkedQueue<Gate>()

    /** Reports the test queues for the next drains; visible to the test class so it can drive them. */
    val reports = ConcurrentLinkedQueue<SyncReport>()

    override fun pushPending(): SyncReport {
        val gate = Gate()
        gate.thread = Thread.currentThread()
        gates.add(gate)
        gate.entered.countDown()
        if (!blocking) {
            // Fail-fast mode: return at once so a test that would block the caller's thread
            // fails on its assertion instead of hanging the suite.
            gate.finished.countDown()
            return reports.poll() ?: SyncReport(1, 0)
        }
        try {
            gate.released.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            gate.finished.countDown()
            return SyncReport(0, 1)
        }
        val report = reports.poll() ?: SyncReport(1, 0)
        gate.finished.countDown()
        return report
    }

    override fun enqueue(transcription: Transcription) {
        // Not used by the worker.
    }

    val callCount: Int get() = gates.size

    private fun gate(n: Int): Gate =
        gates.elementAt(n - 1) ?: throw AssertionError("no pushPending call $n")

    fun awaitEntered(n: Int) {
        waitFor("$n pushPending call(s) to start") { gates.size >= n }
        gate(n).entered.await(5, TimeUnit.SECONDS)
    }

    fun awaitFinished(n: Int) {
        waitFor("$n pushPending call(s) to finish") { gates.count { it.finished.count == 0L } >= n }
    }

    fun release(n: Int) {
        gate(n).released.countDown()
    }

    fun threadOf(n: Int): Thread? = gate(n).thread
}

internal class FakeSyncScheduler : SyncScheduler {
    val soon = mutableListOf<() -> Unit>()
    val delayed = mutableListOf<Delayed>()
    data class Delayed(val delayMs: Long, val block: () -> Unit, val handle: FakeHandle)

    override fun runSoon(block: () -> Unit) {
        soon.add(block)
    }

    override fun runAfter(delayMs: Long, block: () -> Unit): SyncScheduler.Handle {
        val handle = FakeHandle()
        val entry = Delayed(delayMs, block, handle)
        delayed.add(entry)
        return handle
    }

    /** Simulates the clock reaching the armed delay: fires the pending delayed run. */
    fun firePending() {
        val entry = delayed.lastOrNull()
            ?: throw AssertionError("no pending delayed run to fire")
        delayed.remove(entry)
        entry.block()
    }

    /** The delays still armed; a cancelled handle stays in [delayed] so a late fire can be proven, but it is no longer armed. */
    fun delays(): List<Long> = delayed.filterNot { it.handle.cancelled }.map { it.delayMs }

    class FakeHandle : SyncScheduler.Handle {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }
}

internal class ManualClock {
    var nowMillis: Long = 0
    fun advance(millis: Long) {
        nowMillis += millis
    }
}

/** The [Background] port: one new test thread per submit, so a drain never runs inline. */
internal class SyncFakeBackground : Background {
    val threads = mutableListOf<Thread>()
    override fun submit(block: () -> Unit) {
        val thread = Thread({ block() }, "test-bg")
        threads.add(thread)
        thread.start()
    }
}

/** Build a worker over the fakes; [gate] is the only part each test class supplies. */
internal fun makeWorker(
    gate: () -> AuthSession?,
    service: FakeSyncService = FakeSyncService(),
    scheduler: FakeSyncScheduler = FakeSyncScheduler(),
    background: SyncFakeBackground = SyncFakeBackground(),
    pendingCount: () -> Int = { 0 },
): SyncWorker = SyncWorker(service, background, scheduler, gate, pendingCount)

/**
 * Wait for call [n] to enter pushPending, release it, wait for it to return and for the
 * background thread to end, so the worker's finish() has armed the next run already.
 */
internal fun drainOnce(service: FakeSyncService, n: Int) {
    service.awaitEntered(n)
    service.release(n)
    service.awaitFinished(n)
    service.threadOf(n)?.join(5_000)
}

internal fun advanceAndFire(clock: ManualClock, scheduler: FakeSyncScheduler, millis: Long) {
    clock.advance(millis)
    scheduler.firePending()
}
