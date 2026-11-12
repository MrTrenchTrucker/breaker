package dev.breaker.dictation

import dev.breaker.dictation.history.PurgeReport
import dev.breaker.dictation.wiring.BackfillWalk

/**
 * The start-up sequence after an interrupted sign-out clear, as one port-taking function: run the
 * start-up purge under its clock-skip guard, and backfill pending tombstones when sync is on and no
 * clear just finished. The backfill is handed the guard's outcome, so the tombstone sweep that follows
 * the backfill can sit under the same clock guard. Returns the guard's outcome so the caller learns
 * what the purge did.
 *
 * The logic sits here, not inline in BreakerApp, so a test can drive the real sequence: the guard's
 * decision and the backfill gate. A source pin holds BreakerApp to calling this, not its own copy.
 */
internal fun runStartUpAfterClear(
    clearFinished: Boolean,
    syncOn: Boolean,
    now: Long,
    autoTimeOn: Boolean,
    purgeStore: PurgeBookkeepingStore,
    purge: () -> PurgeReport,
    backfill: (PurgeOutcome) -> Unit,
): PurgeOutcome {
    val outcome = runGuardedStartPurge(now = now, autoTimeOn = autoTimeOn, store = purgeStore, purge = purge)
    if (syncOn && !clearFinished) backfill(outcome)
    return outcome
}

/**
 * Runs [sweep] only when the row purge ran under its clock guard ([outcome] is Purged) and the backfill
 * walk reached the end of the tombstones. A walk cut by its page cap, or a purge the guard skipped, sweeps
 * nothing: a tombstone must never expire before its delete is queued. Answers whether the sweep ran.
 */
internal fun sweepAfterCompleteWalk(outcome: PurgeOutcome, walk: BackfillWalk, sweep: () -> Unit): Boolean {
    if (outcome !is PurgeOutcome.Purged || !walk.complete) return false
    sweep()
    return true
}

/**
 * The whole tombstone step of the start-up: the walk first, then the sweep decision. A walk that throws never
 * reaches the sweep, and the failure goes to the caller, which swallows it and retries on the next start.
 */
internal fun backfillThenSweep(outcome: PurgeOutcome, walk: () -> BackfillWalk, sweep: () -> Unit): Boolean =
    sweepAfterCompleteWalk(outcome, walk(), sweep)
