package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.shared.api.UpdateManifest

/**
 * One update check that also recognises a rollback offer. For every offer that is not a rollback
 * it answers exactly as [UpdateCheck] does, so the normal update button never offers a downgrade.
 * A rollback offer answers up to date with the installed name and is kept in [held] for the admin
 * rollback button. The installed code and name are read before [held] changes, so a check that
 * cannot finish leaves [held] as it was. It blocks and never throws: a check that cannot finish is
 * [UpdateCheckResult.Failed] with a fixed [CheckReasons] sentence.
 */
internal class RollbackAwareCheck(
    private val fetch: ManifestFetch,
    private val installedVersionCode: () -> Long,
    private val installedVersionName: () -> String,
    private val held: HeldRollback,
) {
    fun check(): UpdateCheckResult =
        try {
            when (val outcome = fetch.fetch()) {
                is FetchOutcome.Manifest -> decide(outcome.manifest)
                FetchOutcome.NoRelease -> {
                    val name = installedVersionName()
                    held.clear()
                    UpdateCheckResult.UpToDate(name)
                }
                is FetchOutcome.Failed -> UpdateCheckResult.Failed(outcome.reason)
            }
        } catch (_: Exception) {
            UpdateCheckResult.Failed(CheckReasons.UNFINISHED)
        }

    private fun decide(manifest: UpdateManifest): UpdateCheckResult {
        val code = installedVersionCode()
        val name = installedVersionName()
        val rollback = RollbackOffer.recognise(code, name, manifest)
        if (rollback != null) {
            held.hold(rollback)
            return UpdateCheckResult.UpToDate(name)
        }
        held.clear()
        return when (val decision = UpdateDecision.of(code, manifest)) {
            is UpdateDecision.Newer -> UpdateCheckResult.Available(decision.release)
            UpdateDecision.NotNewer -> UpdateCheckResult.UpToDate(name)
            UpdateDecision.Unusable -> UpdateCheckResult.Failed(CheckReasons.UNUSABLE)
        }
    }
}
