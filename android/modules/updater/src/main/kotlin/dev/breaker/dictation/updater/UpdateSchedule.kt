package dev.breaker.dictation.updater

/** What an update call should do. */
internal enum class ScheduleDecision {
    /** An automatic check that is due: run it. */
    Check,

    /** An admin asked for a check now: run it whatever the schedule says. */
    CheckForced,

    /** An automatic call that is not due, or is held by the gap after the last attempt: do nothing. */
    NotDue,
}

/** One day in milliseconds: an automatic check is due this long after the last one. */
internal const val DAY_MILLIS: Long = 86_400_000L

/** The least time in milliseconds between two attempts that an automatic call respects. */
internal const val MIN_ATTEMPT_GAP_MILLIS: Long = 3_600_000L

/**
 * Decides what an update call should do, from the ledger times and the caller's role.
 *
 * The caller passes [nowMillis] from its clock; this function reads no clock. An admin's forced
 * call is always CheckForced. Every other call is automatic, a non-admin forced call included:
 * it is Check when the day has passed and no attempt holds it, and NotDue otherwise.
 */
internal fun decideUpdateCheck(
    nowMillis: Long,
    lastCheckMillis: Long?,
    lastAttemptMillis: Long?,
    forced: Boolean,
    isAdmin: Boolean,
): ScheduleDecision {
    if (forced && isAdmin) return ScheduleDecision.CheckForced
    if (!isDue(nowMillis, lastCheckMillis)) return ScheduleDecision.NotDue
    if (isHeldByGap(nowMillis, lastAttemptMillis)) return ScheduleDecision.NotDue
    return ScheduleDecision.Check
}

/** True when an automatic check is due: never checked, checked in the future, or a day or more ago. */
private fun isDue(nowMillis: Long, lastCheckMillis: Long?): Boolean {
    if (lastCheckMillis == null) return true
    if (lastCheckMillis > nowMillis) return true
    val elapsed = nowMillis - lastCheckMillis
    // lastCheckMillis is not after nowMillis, so the true gap is never negative: a negative result
    // means the subtraction overflowed, which is a gap far larger than a day.
    return elapsed < 0L || elapsed >= DAY_MILLIS
}

/** True when the last attempt is less than the minimum gap ago; an attempt in the future holds nothing. */
private fun isHeldByGap(nowMillis: Long, lastAttemptMillis: Long?): Boolean {
    if (lastAttemptMillis == null) return false
    if (lastAttemptMillis > nowMillis) return false
    val elapsed = nowMillis - lastAttemptMillis
    // The same reasoning: a negative result is an overflowed, very old attempt, which holds nothing.
    return elapsed >= 0L && elapsed < MIN_ATTEMPT_GAP_MILLIS
}
