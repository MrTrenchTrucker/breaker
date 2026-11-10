package dev.breaker.dictation.sync

/**
 * Runs one pull cycle in order: read the stored cursor, pull, apply, and only then save.
 * After a finished full list one more pull runs from the list's cursor; a second refusal
 * of the stored cursor clears it. At most two pull runs; QueuedSyncService runs it after each push drain.
 */
internal class PullCycle(
    private val transport: PullTransport,
    private val cursors: PullCursorPort,
    private val applier: PullApplier,
) {
    /** Runs one cycle and reports what happened. */
    fun run(): CycleReport {
        val stored = cursors.pullCursor()
        val run1 = PullRun.run(transport, stored)
        return when (run1.outcome) {
            is PullOutcome.Ok -> {
                val first = applier.apply(run1)
                cursors.savePullCursor(run1.outcome.next)
                if (run1.fullList) {
                    val run2 = PullRun.run(transport, run1.outcome.next)
                    return when (run2.outcome) {
                        is PullOutcome.Ok -> {
                            val second = applier.apply(run2)
                            cursors.savePullCursor(run2.outcome.next)
                            CycleReport(first, second, null, true, false)
                        }
                        else -> {
                            val reason = reasonOf(run2.outcome)
                            CycleReport(first, null, reason, true, false)
                        }
                    }
                }
                CycleReport(first, null, null, true, false)
            }
            else -> {
                val reason = reasonOf(run1.outcome)
                val cleared = run1.outcome is PullOutcome.Refused &&
                    run1.outcome.reason == PullRun.LIST_AGAIN_REFUSED &&
                    stored != null && stored.isNotEmpty()
                if (cleared) {
                    cursors.clearPullCursor()
                }
                CycleReport(null, null, reason, false, cleared)
            }
        }
    }

    private fun reasonOf(outcome: PullOutcome): String {
        return when (outcome) {
            is PullOutcome.BadCursor -> "the server refused the cursor"
            PullOutcome.Gone -> "the cursor is below the purged floor"
            is PullOutcome.Other -> "the server answered status ${outcome.status}"
            PullOutcome.Unreachable -> "the server could not be reached"
            is PullOutcome.Refused -> outcome.reason
            else -> throw IllegalStateException("unknown outcome")
        }
    }
}
