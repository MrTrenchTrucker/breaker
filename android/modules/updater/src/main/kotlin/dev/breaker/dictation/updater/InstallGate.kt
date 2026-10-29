package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.model.UpdateInstallResult
import dev.breaker.dictation.core.port.UpdateChecker

/**
 * The update checker as the app uses it. A check is passed through unchanged.
 *
 * An install first runs the checker's guard, which reads the installed build and writes nothing, then the first-start
 * decision for the start rules, and then the checker's install. The checker's install runs the guard once more; it has
 * no side effect, so the second run cannot change the answer.
 */
internal class InstallGate(private val checker: ReleaseUpdateChecker, private val start: StartRun) : UpdateChecker {
    override fun check(force: Boolean): UpdateCheckResult? = checker.check(force)

    override fun install(release: ReleaseInfo): UpdateInstallResult {
        checker.installGuard(release)?.let { return it }
        return when (val step = start.beforeInstall()) {
            InstallStep.Go -> checker.install(release)
            is InstallStep.Blocked -> UpdateInstallResult.Refused(step.sentence)
            InstallStep.WriteFailed -> UpdateInstallResult.Failed(UpdateSentences.INSTALLER_FAILED)
        }
    }
}
