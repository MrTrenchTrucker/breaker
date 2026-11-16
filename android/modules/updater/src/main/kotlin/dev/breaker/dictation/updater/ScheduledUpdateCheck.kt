package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult

/**
 * Joins the due decision, one check and the record of the check time.
 *
 * The daily check asks whether it is time yet, runs [runCheck] when it is, and remembers when that
 * happened. Nothing else: no timer, no download, no install, no rollback, no app wiring. Every
 * collaborator is passed in, so a test fakes the clock, the stored time and the recorder.
 *
 * Never throws. An exception from [nowMs], [lastCheckAtMs] or [runCheck] (an interrupt included)
 * answers [UpdateCheckResult.Failed] with the fixed sentence [CheckReasons.UNFINISHED], and
 * records nothing. A recorder that throws after a real answer changes no answer: the answer is
 * still returned, best effort.
 */
internal class ScheduledUpdateCheck(
    private val runCheck: () -> UpdateCheckResult,
    private val lastCheckAtMs: () -> Long?,
    private val recordCheckAtMs: (Long) -> Unit,
    private val nowMs: () -> Long,
) {
    /**
     * Runs [runCheck] once when it is due or when [force] asks for it, and records the time of the
     * call. Returns null when the check was neither due nor forced, without calling [runCheck] and
     * without recording anything.
     */
    fun check(force: Boolean): UpdateCheckResult? {
        val now = try {
            nowMs()
        } catch (_: Exception) {
            return UpdateCheckResult.Failed(CheckReasons.UNFINISHED)
        }

        val due = try {
            UpdateSchedule.isDue(lastCheckAtMs(), now)
        } catch (_: Exception) {
            return UpdateCheckResult.Failed(CheckReasons.UNFINISHED)
        }

        if (!force && !due) return null

        val outcome = try {
            runCheck()
        } catch (_: Exception) {
            UpdateCheckResult.Failed(CheckReasons.UNFINISHED)
        }

        if (outcome is UpdateCheckResult.UpToDate || outcome is UpdateCheckResult.Available) {
            try {
                recordCheckAtMs(now)
            } catch (_: Exception) {
                // best effort: the answer does not depend on the record
            }
        }
        return outcome
    }
}
