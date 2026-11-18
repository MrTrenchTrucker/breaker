package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.port.UpdateChecker

/**
 * The app's update checker: the daily check, the admin force check, and the install of a verified
 * release. Every step is passed in, so a test fakes each one.
 *
 * Every call blocks, so the caller runs it off the UI thread. Nothing here throws. A check that cannot
 * run answers [UpdateCheckResult.Failed]. An install answers through [onInstallResult]
 * with [InstallResult.Started] or a refusal. A report that throws changes no answer.
 */
internal class UpdateCheckerImpl(
    private val runScheduled: (Boolean) -> UpdateCheckResult?,
    private val isAdmin: () -> Boolean,
    private val verifyRelease: (ReleaseInfo) -> UpdateVerificationResult,
    private val installReady: (UpdateVerificationResult.Ready) -> InstallResult,
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

    /** Verifies [release], starts the install of a verified file, and reports the answer. */
    private fun finish(release: ReleaseInfo) {
        val result: InstallResult = try {
            when (val verified = verifyRelease(release)) {
                is UpdateVerificationResult.Refused -> InstallResult.Refused(verified.reason)
                is UpdateVerificationResult.Ready -> installReady(verified)
            }
        } catch (_: Exception) {
            InstallResult.Refused(UpdateVerificationReasons.UNEXPECTED)
        }
        report(result)
    }

    private fun adminOrFalse(): Boolean =
        try {
            isAdmin()
        } catch (_: Exception) {
            false
        }

    private fun report(result: InstallResult) {
        try {
            onInstallResult(result)
        } catch (_: Exception) {
            // best effort: the answer that is reported does not depend on the report
        }
    }
}
