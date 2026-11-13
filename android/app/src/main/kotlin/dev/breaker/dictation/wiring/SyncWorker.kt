package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.SyncReport
import dev.breaker.dictation.core.port.SyncService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Starts drains of the sync queue: after each save, at app start, and on the backoff
 * timer while the process lives.
 *
 * One drain at a time, always on the [Background] thread. `pushPending` blocks
 * (runBlocking inside the connectivity probe), so it never runs on the caller's thread.
 * Triggers that arrive while a drain is pending or running are coalesced into the single
 * run — they may still reset the backoff, but they never queue a second run.
 *
 * Backoff: 30 s, 1 min, 2 min, 4 min, then 15 min and stays there. It resets on a
 * confirmed drain, on a new save, or when the session token differs from the one that
 * failed. No drain ever starts without a session: the gate is checked when a trigger
 * arrives and again before the drain runs, so a 401 can never hot-loop — the next run
 * is always a delayed run, never an immediate retry.
 *
 * Coordination is atomics only. The privacy scan bans `synchronized` in `wiring/`
 * (AppPrivacyScanTest, the "synchronized" rule), so nothing here takes a monitor. The
 * states are [running] (a drain is in flight), [pending] (a delayed re-arm is armed),
 * [stopped], [failures] and [lastFailedToken]. A monitor gave the transitions for free;
 * with atomics each one is explicit: the single-run guarantee is the
 * [AtomicBoolean.compareAndSet] in [startRun], and [arm] re-checks [stopped] after it
 * sets [pending] so a [stop] that lands mid-schedule still cancels the handle.
 */
class SyncWorker(
    private val service: SyncService,
    private val background: Background,
    private val scheduler: SyncScheduler,
    private val sessionGate: () -> AuthSession?,
    /**
     * How many rows are still queued. Asked only when a drain reported no push and no failure,
     * so the worker can tell an empty queue from an unreachable server: pushPending returns
     * (0, 0) for both.
     */
    private val pendingCount: () -> Int = { 0 },
) {

    /** A drain is running on the background thread. */
    private val running = AtomicBoolean(false)

    /** A delayed re-arm is armed; null when idle. */
    private val pending = AtomicReference<SyncScheduler.Handle?>(null)

    /** Consecutive failed drains; indexes the backoff sequence below. */
    private val failures = AtomicReference<Int>(0)

    /** The session token of the last failed drain, or null when none has failed. */
    private val lastFailedToken = AtomicReference<String?>(null)

    /** Set by [stop]: an in-flight drain finishes but never re-arms. */
    private val stopped = AtomicBoolean(false)

    /** After each save. A new save always resets the backoff. */
    fun onSave() = trigger(newSave = true)

    /** At app start. */
    fun onStart() = trigger(newSave = false)

    /** Stops everything: no further triggers start a drain and the pending delayed run is cancelled.
     * A drain already in flight finishes, but never re-arms. */
    fun stop() {
        stopped.set(true)
        pending.getAndSet(null)?.cancel()
    }

    private fun trigger(newSave: Boolean) {
        if (stopped.get()) return
        // No session: never start a drain. The gate is checked again before the drain runs.
        val token = sessionGate()?.token ?: return
        // A new save, or a session different from the one that failed, resets the backoff and
        // cancels the armed timer so the new row goes out now instead of waiting on it.
        if (newSave || lastFailedToken.get()?.let { it != token } == true) {
            failures.set(0)
            lastFailedToken.set(null)
            pending.getAndSet(null)?.cancel()
        }
        // Coalesce: a drain running swallows the trigger; an armed timer that was not cancelled
        // above (same token, no new save) still owns the next run.
        if (running.get() || pending.get() != null) return
        startRun()
    }

    private fun startRun() {
        if (stopped.get()) return
        // The one place a run begins: a trigger and a fired backoff timer can arrive together,
        // and only the winner of this swap runs. A submit that throws releases the claim.
        if (!running.compareAndSet(false, true)) return
        try {
            background.submit { runOnce() }
        } catch (e: Exception) {
            running.set(false)
        }
    }

    /** On the background thread. */
    private fun runOnce() {
        val report: SyncReport = try {
            val session = sessionGate()
            if (session == null) SyncReport(0, 0) else service.pushPending()
        } catch (e: Exception) {
            SyncReport(0, 1)
        }
        finish(report)
    }

    private fun finish(report: SyncReport) {
        running.set(false)
        if (stopped.get()) return
        val token = sessionGate()?.token
        when {
            report.failed > 0 -> {                       // failed drain (401 included): back off
                // even when rows also pushed: a partial drain still backs off.
                if (report.pushed > 0) failures.set(0)   // reset FIRST -> delayFor(1) = 30 s, not the next step
                val next = failures.get() + 1
                failures.set(next)
                lastFailedToken.set(token)
                arm(maxDelayMs(delayFor(next), report.retryAfterSeconds))
            }
            report.pushed > 0 -> {                       // confirmed drain: reset the backoff
                failures.set(0)
                lastFailedToken.set(null)
            }
            else -> {
                // A drain that pushed nothing and failed nothing is either an empty queue or an
                // unreachable server; SyncReport cannot tell them apart (pushPending returns
                // (0,0) for both). Ask how many rows are still queued.
                val stillPending = try { pendingCount() } catch (_: Exception) { Int.MAX_VALUE }
                if (stillPending > 0) {
                    // The server was unreachable: count it as a failure exactly like a failed drain.
                    val next = failures.get() + 1
                    failures.set(next)
                    lastFailedToken.set(token)
                    arm(delayFor(next))
                }
                // stillPending == 0: the queue is empty. Arm nothing, go idle until the next
                // save or app start.
            }
        }
    }

    /** Arms one delayed re-arm. Re-checks [stopped] so a stop that lands mid-schedule cancels it. */
    private fun arm(delayMs: Long) {
        val handle: SyncScheduler.Handle = scheduler.runAfter(delayMs) {
            pending.set(null)
            startRun()
        }
        pending.set(handle)
        if (stopped.get()) pending.getAndSet(null)?.cancel()
    }

    /** 30 s, 1 min, 2 min, 4 min, then 15 min and stays there. */
    private fun delayFor(failures: Int): Long =
        when (failures) {
            1 -> 30_000
            2 -> 60_000
            3 -> 120_000
            4 -> 240_000
            else -> 900_000
        }
}

/** The wait after a failed drain: the backoff step, or the server's Retry-After when that is longer. */
internal fun maxDelayMs(backoffStepMs: Long, retryAfterSeconds: Int?): Long =
    maxOf(backoffStepMs, (retryAfterSeconds ?: 0).toLong() * 1000L)
