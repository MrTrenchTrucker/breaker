package dev.breaker.dictation

import dev.breaker.dictation.history.PurgeReport

/**
 * The start-up purge's decision: run it now ([GO]) or skip it and report clock
 * skew ([DEFERRED_SKEW]).
 *
 * The rule, in full:
 * - Automatic time ON -> [GO]. The clock is trustworthy, so the retention purge
 *   runs strictly (delete the rows older than the window), exactly as ADR-010
 *   says. A network clock cannot have jumped forward on its own.
 * - Automatic time OFF and no purge has ever run ([lastPurgeAt] is unset) ->
 *   [GO]. There is no baseline to compare against, so there is no jump to
 *   suspect; run the purge and record the instant it ran.
 * - Automatic time OFF and now is at most `graceDays` past [lastPurgeAt] ->
 *   [GO]. Nothing suggests a jump yet; the rows are not overrun.
 * - Automatic time OFF and more than `graceDays` past [lastPurgeAt] -> a forward
 *   jump is SUSPECTED. Skip the purge and report skew, for at most `capDays`
 *   counted from the first skipped start ([deferralStartedAt]); once the cap is
 *   reached, purge as normal ([GO]).
 *
 * The trigger keys on [lastPurgeAt], the last purge that actually ran, never on
 * the newest record: a row stamped under a wrong clock does not end the
 * protection. The decision is a pure function of these four instants and the
 * automatic-time flag, so it is tested on a plain JVM.
 */
object PurgeGuard {

    /** Days an old [lastPurgeAt] may sit before a forward jump is suspected, when automatic time is off. */
    const val GRACE_DAYS: Long = 30

    /** Days a suspected jump is deferred for, at most, before the purge runs regardless. */
    const val CAP_DAYS: Long = 7

    private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    fun decide(
        now: Long,
        autoTimeOn: Boolean,
        lastPurgeAt: Long,
        deferralStartedAt: Long,
        graceDays: Long = GRACE_DAYS,
        capDays: Long = CAP_DAYS,
    ): PurgeDecision {
        if (autoTimeOn) return PurgeDecision.GO
        if (lastPurgeAt == PurgeBookkeepingStore.UNSET) return PurgeDecision.GO
        val jumpSuspected = now > lastPurgeAt + graceDays * MS_PER_DAY
        if (!jumpSuspected) return PurgeDecision.GO
        if (deferralStartedAt == PurgeBookkeepingStore.UNSET) return PurgeDecision.DEFERRED_SKEW
        if (now >= deferralStartedAt + capDays * MS_PER_DAY) return PurgeDecision.GO
        return PurgeDecision.DEFERRED_SKEW
    }
}

/** Whether the start-up purge runs now or is skipped for a suspected clock skew. */
enum class PurgeDecision { GO, DEFERRED_SKEW }

/**
 * The start-up purge's own result, decided by the app (not history): it either
 * ran and carries history's [PurgeReport] ([Purged]), or it was skipped for a
 * suspected forward clock jump ([Skipped]). The skip is the app guard's call, so
 * this type lives here in the app, never in the history module.
 */
sealed interface PurgeOutcome {
    /** The purge ran; [report] is history's own report. */
    data class Purged(val report: PurgeReport) : PurgeOutcome

    /** The purge was skipped for a suspected forward clock jump. */
    data object Skipped : PurgeOutcome
}

/**
 * Runs the start-up purge under [PurgeGuard], off the caller's thread (the app
 * schedules it on the I/O scope). It reads the two bookkeeping instants, decides,
 * and does exactly one of:
 * - [PurgeDecision.GO]: run [purge], then record `lastPurgeAt = now` and clear
 *   the deferral, so a later jump gets its own fresh cap. The result is
 *   [Purged], carrying the purge's own report.
 * - [PurgeDecision.DEFERRED_SKEW]: record `deferralStartedAt = now` when no
 *   deferral is running yet, and return [Skipped]; the skip is the result.
 *
 * [purge] is the real retention cleanup (`history.purgeExpired()`); the returned
 * [PurgeOutcome] is how the skip is made observable to the app, so this stays
 * testable on a plain JVM.
 */
fun runGuardedStartPurge(
    now: Long,
    autoTimeOn: Boolean,
    store: PurgeBookkeepingStore,
    purge: () -> PurgeReport,
): PurgeOutcome {
    val deferralStartedAt = store.deferralStartedAt()
    val decision = PurgeGuard.decide(
        now = now,
        autoTimeOn = autoTimeOn,
        lastPurgeAt = store.lastPurgeAt(),
        deferralStartedAt = deferralStartedAt,
    )
    return when (decision) {
        PurgeDecision.GO -> {
            val report = purge()
            store.recordLastPurgeAt(now)
            store.clearDeferralStartedAt()
            PurgeOutcome.Purged(report)
        }
        PurgeDecision.DEFERRED_SKEW -> {
            if (deferralStartedAt == PurgeBookkeepingStore.UNSET) store.recordDeferralStartedAt(now)
            PurgeOutcome.Skipped
        }
    }
}
