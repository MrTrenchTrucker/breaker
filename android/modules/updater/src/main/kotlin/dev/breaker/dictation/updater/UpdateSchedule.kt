package dev.breaker.dictation.updater

/**
 * When the next update check is due.
 *
 * The daily check asks "is it time yet?" before it touches the network. This object answers
 * that question from two numbers and nothing else: it reads no clock, keeps no state and
 * imports no Android class, so the caller passes the time and the test can pick any time.
 *
 * It is `internal` on purpose: nothing outside the module calls it until the checker does.
 */
internal object UpdateSchedule {
    /** One day in milliseconds. A check is due only when MORE than this has passed. */
    private const val DAILY_MS = 86_400_000L

    /**
     * True when a check is due at [nowMs], given the time of the last check [lastCheckAtMs]
     * (milliseconds since the epoch, or null when the app never checked).
     *
     * Due in three cases: never checked; [nowMs] is before the last check (the clock moved
     * back: a check is cheap and a stuck updater is not); more than a day has passed, strictly,
     * so exactly one day is not yet due.
     *
     * The gap is taken with an exact subtraction. A gap too large for a Long throws, and a gap
     * that large is far more than a day, so it counts as due.
     */
    fun isDue(lastCheckAtMs: Long?, nowMs: Long): Boolean {
        if (lastCheckAtMs == null) return true
        val gap = try {
            Math.subtractExact(nowMs, lastCheckAtMs)
        } catch (e: ArithmeticException) {
            return true
        }
        return gap < 0L || gap > DAILY_MS
    }
}
