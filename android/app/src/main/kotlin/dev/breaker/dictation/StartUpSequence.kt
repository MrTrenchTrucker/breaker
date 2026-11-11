package dev.breaker.dictation

import dev.breaker.dictation.history.PurgeReport

/**
 * The start-up sequence after an interrupted sign-out clear, as one port-taking function: run the
 * start-up purge under its clock-skip guard, and backfill pending tombstones when sync is on and no
 * clear just finished. Returns the guard's outcome so the caller learns what the purge did.
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
    backfill: () -> Unit,
): PurgeOutcome {
    val outcome = runGuardedStartPurge(now = now, autoTimeOn = autoTimeOn, store = purgeStore, purge = purge)
    if (syncOn && !clearFinished) backfill()
    return outcome
}
