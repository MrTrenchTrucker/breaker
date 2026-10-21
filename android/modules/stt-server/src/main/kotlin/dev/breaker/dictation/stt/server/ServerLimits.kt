package dev.breaker.dictation.stt.server

/**
 * Time and size limits for one transcription call to the server.
 *
 * Every time value is in milliseconds and is checked against [MAX_MS] when the object is built,
 * as are the counts. A value outside its range is refused with an exception whose message names
 * the limit but never the value. The defaults are starting values; they were not measured on a
 * device or against a real server.
 */
data class ServerLimits(
    /** Connect timeout for each call: the first POST and every poll. */
    val connectTimeoutMs: Long = 1_500L,
    /** Time to wait for the answer to the POST after its body is sent. */
    val postReplyTimeoutMs: Long = 10_000L,
    /** Hard cap on the whole POST exchange. */
    val postCallCapMs: Long = 30_000L,
    /** Read timeout for one poll. */
    val pollReadTimeoutMs: Long = 5_000L,
    /** Hard cap on one poll exchange. */
    val pollCallCapMs: Long = 10_000L,
    /** Pause before the first poll. */
    val firstPollDelayMs: Long = 500L,
    /** Largest pause between two polls. */
    val pollDelayCapMs: Long = 2_000L,
    /** Budget for a call with an empty clip. */
    val baseBudgetMs: Long = 60_000L,
    /** Budget grows by this many milliseconds for each millisecond of clip. */
    val clipFactor: Int = 3,
    /** Largest total budget for one call, however long the clip. */
    val maxBudgetMs: Long = 300_000L,
    /** Largest number of polls in one call. */
    val maxPolls: Int = 600,
    /** Consecutive transient poll failures tolerated before the call ends. */
    val maxTransientPollFailures: Int = 3,
) {
    init {
        require(connectTimeoutMs in 1L..MAX_MS) { "connect timeout is out of range" }
        require(postReplyTimeoutMs in 1L..MAX_MS) { "post reply timeout is out of range" }
        require(postCallCapMs in 1L..MAX_MS) { "post call cap is out of range" }
        require(pollReadTimeoutMs in 1L..MAX_MS) { "poll read timeout is out of range" }
        require(pollCallCapMs in 1L..MAX_MS) { "poll call cap is out of range" }
        require(firstPollDelayMs in 1L..MAX_MS) { "first poll delay is out of range" }
        require(pollDelayCapMs in 1L..MAX_MS) { "poll delay cap is out of range" }
        require(baseBudgetMs in 1L..MAX_MS) { "base budget is out of range" }
        require(maxBudgetMs in 1L..MAX_MS) { "max budget is out of range" }
        require(clipFactor in 0..MAX_CLIP_FACTOR) { "clip factor is out of range" }
        require(maxPolls in 1..MAX_POLLS) { "max polls is out of range" }
        require(maxTransientPollFailures in 0..MAX_FAILURES) { "transient failure limit is out of range" }
        require(maxBudgetMs >= baseBudgetMs) { "max budget is below the base budget" }
        require(firstPollDelayMs <= pollDelayCapMs) { "first poll delay is above the poll delay cap" }
        require(postReplyTimeoutMs <= postCallCapMs) { "post reply timeout is above the post call cap" }
        require(pollReadTimeoutMs <= pollCallCapMs) { "poll read timeout is above the poll call cap" }
    }

    /**
     * Budget in milliseconds for a call whose clip lasts [clipMs]: the base budget plus
     * [clipFactor] times the clip, never more than [maxBudgetMs]. A negative clip counts as zero.
     *
     * The multiplication is done only when it cannot overflow: if the clip is longer than the
     * room left above the base budget, the answer is the cap itself.
     */
    fun budgetFor(clipMs: Long): Long {
        if (clipFactor == 0) return baseBudgetMs
        val clip = if (clipMs < 0L) 0L else clipMs
        val room = maxBudgetMs - baseBudgetMs
        return if (clip <= room / clipFactor) baseBudgetMs + clipFactor * clip else maxBudgetMs
    }

    companion object {
        /** Largest value allowed for any time limit, in milliseconds. */
        const val MAX_MS: Long = 600_000L

        /** Largest value allowed for [maxPolls]. */
        const val MAX_POLLS: Int = 600

        /** Largest value allowed for [maxTransientPollFailures]. */
        const val MAX_FAILURES: Int = 10

        /** Largest value allowed for [clipFactor]. */
        const val MAX_CLIP_FACTOR: Int = 10
    }
}
