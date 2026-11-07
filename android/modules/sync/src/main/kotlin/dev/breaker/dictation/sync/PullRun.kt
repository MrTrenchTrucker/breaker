package dev.breaker.dictation.sync

/** The result of one list run: the outcome and whether it was a full list from scratch. */
internal class PullRunResult(val outcome: PullOutcome, val fullList: Boolean)

/** Applies the list-again-once rule to one pull. Nothing is stored and nothing is handed to history. */
internal object PullRun {
    /** The list was refused (no cursor, and 400 or 410 came back). */
    internal const val LIST_REFUSED = "the list was refused"
    /** Listed again once and refused again (cursor dropped, second attempt refused). */
    internal const val LIST_AGAIN_REFUSED = "listed again once and refused again"

    /**
     * Runs one pull under the list-again-once rule.
     *
     * With no stored cursor: one PullLoop.run call with null. Ok gives fullList true. BadCursor or
     * Gone gives Refused LIST_REFUSED with exactly one call. Other, Unreachable and Refused come
     * back unchanged with fullList false.
     *
     * With a stored cursor: first PullLoop.run with that cursor. Ok gives fullList false. Other,
     * Unreachable and Refused come back unchanged with fullList false and one call only. BadCursor
     * or Gone drops the cursor and lists again once with null. Ok gives fullList true with only the
     * second attempt's records. BadCursor or Gone again gives Refused LIST_AGAIN_REFUSED with no
     * third call. Other, Unreachable and Refused come back unchanged with fullList false.
     *
     * Never more than two PullLoop.run calls. A transport exception propagates.
     */
    fun run(transport: PullTransport, storedCursor: String? = null): PullRunResult {
        val cursor = storedCursor
        if (cursor == null || cursor.isEmpty()) {
            return runOnce(transport, again = false, fullList = true)
        }
        val first = PullLoop.run(transport, cursor)
        when (first) {
            is PullOutcome.Ok -> return PullRunResult(first, fullList = false)
            is PullOutcome.BadCursor, is PullOutcome.Gone -> {
                return runOnce(transport, again = true, fullList = true)
            }
            else -> return PullRunResult(first, fullList = false)
        }
    }

    private fun runOnce(
        transport: PullTransport,
        again: Boolean,
        fullList: Boolean,
    ): PullRunResult {
        val outcome = PullLoop.run(transport, null)
        return when (outcome) {
            is PullOutcome.Ok -> PullRunResult(outcome, fullList)
            PullOutcome.BadCursor,
            PullOutcome.Gone -> {
                if (again) {
                    PullRunResult(PullOutcome.Refused(LIST_AGAIN_REFUSED), fullList = false)
                } else {
                    PullRunResult(PullOutcome.Refused(LIST_REFUSED), fullList = false)
                }
            }
            else -> PullRunResult(outcome, fullList = false)
        }
    }
}
