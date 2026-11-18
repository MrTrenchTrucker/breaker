package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.port.UpdateChecker

/**
 * The app's update checker: the daily check, the admin force check, and the install and the rollback
 * of a verified release. Every step is passed in, so a test fakes each one.
 *
 * Every call blocks, so the caller runs it off the UI thread. Nothing here throws. A check that cannot
 * run answers [UpdateCheckResult.Failed]. An install or a rollback answers through [onInstallResult]
 * with [InstallResult.Started] or a refusal. A report that throws changes no answer.
 */
internal class UpdateCheckerImpl(
    private val runScheduled: (Boolean) -> UpdateCheckResult?,
    private val isAdmin: () -> Boolean,
    private val verifyRelease: (ReleaseInfo) -> UpdateVerificationResult,
    private val installReady: (UpdateVerificationResult.Ready) -> InstallResult,
    private val held: HeldRollback,
    private val onInstallResult: (InstallResult) -> Unit,
) : UpdateChecker {

    override fun check(force: Boolean): UpdateCheckResult? {
        val allowForce = force && adminOrFalse()
        return try {
            runScheduled(allowForce)
        } catch (_: Exception) {
            UpdateCheckResult.Failed(CheckReasons.UNFINISHED)
        }
    }

    override fun install(release: ReleaseInfo) {
        finish(release)
    }

    override fun rollback() {
        if (!adminOrFalse()) {
            report(InstallResult.Refused(RollbackReasons.NOT_ALLOWED))
            return
        }
        val release = heldOrNull()
        if (release == null) {
            report(InstallResult.Refused(RollbackReasons.NONE_HELD))
            return
        }
        finish(release)
    }

    /** Verifies [release], starts the install of a verified file, and reports the answer. */
    private fun finish(release: ReleaseInfo) {
        val result: InstallResult = try {
            when (val verified = verifyRelease(release)) {
                is UpdateVerificationResult.Refused -> InstallResult.Refused(verified.reason)
                is UpdateVerificationResult.Ready -> installAndClear(verified)
            }
        } catch (_: Exception) {
            InstallResult.Refused(UpdateVerificationReasons.UNEXPECTED)
        }
        report(result)
    }

    /** Starts the install; when it started, the held offer is no longer needed and is dropped. */
    private fun installAndClear(ready: UpdateVerificationResult.Ready): InstallResult {
        val answer = installReady(ready)
        if (answer is InstallResult.Started) clearQuietly()
        return answer
    }

    private fun adminOrFalse(): Boolean =
        try {
            isAdmin()
        } catch (_: Exception) {
            false
        }

    private fun heldOrNull(): ReleaseInfo? =
        try {
            held.current()
        } catch (_: Exception) {
            null
        }

    private fun clearQuietly() {
        try {
            held.clear()
        } catch (_: Exception) {
            // best effort: a store that refuses the clear keeps the offer, and the install is still reported
        }
    }

    private fun report(result: InstallResult) {
        try {
            onInstallResult(result)
        } catch (_: Exception) {
            // best effort: the answer that is reported does not depend on the report
        }
    }
}
