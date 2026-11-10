package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.shared.api.UpdateManifest

/**
 * One update check: fetch the latest manifest, compare its version code with the installed one,
 * and say what was found. No schedule (the caller decides when), no download, no signature
 * check, no install. It blocks and never throws: a check that cannot finish is
 * [UpdateCheckResult.Failed] with a fixed [CheckReasons] sentence.
 *
 * The installed code and name are read on every check, from the app.
 */
internal class UpdateCheck(
    private val fetch: ManifestFetch,
    private val installedVersionCode: () -> Long,
    private val installedVersionName: () -> String,
) {
    fun check(): UpdateCheckResult =
        try {
            when (val outcome = fetch.fetch()) {
                is FetchOutcome.Manifest -> decide(outcome.manifest)
                FetchOutcome.NoRelease -> UpdateCheckResult.UpToDate(installedVersionName())
                is FetchOutcome.Failed -> UpdateCheckResult.Failed(outcome.reason)
            }
        } catch (_: Exception) {
            UpdateCheckResult.Failed(CheckReasons.UNFINISHED)
        }

    private fun decide(manifest: UpdateManifest): UpdateCheckResult =
        when (val decision = UpdateDecision.of(installedVersionCode(), manifest)) {
            is UpdateDecision.Newer -> UpdateCheckResult.Available(decision.release)
            UpdateDecision.NotNewer -> UpdateCheckResult.UpToDate(installedVersionName())
            UpdateDecision.Unusable -> UpdateCheckResult.Failed(CheckReasons.UNUSABLE)
        }
}
