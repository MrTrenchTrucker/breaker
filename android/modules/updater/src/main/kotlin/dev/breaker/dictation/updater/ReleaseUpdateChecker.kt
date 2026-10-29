package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.model.UpdateInstallResult
import dev.breaker.dictation.core.port.AuthService
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.UpdateChecker
import java.io.IOException
import java.io.InputStream

/** The largest staged APK that the checker accepts, in bytes: 256 MiB. */
internal const val MAX_APK_BYTES: Long = 268_435_456L

/**
 * Checks the server for a newer release and installs a verified one.
 *
 * A check runs the daily schedule, fetches the manifest, verifies its origin and its signature, and remembers only
 * a release that has passed both. An install takes only a release equal to the remembered one, reads the installed
 * version again, stages the file, checks its digest, its size and that it is not empty, and hands the staged file
 * to the installer. The session is read only to learn whether the caller is an administrator. An expected outcome
 * is a value. A port failure propagates, except an IOException while a stream closes.
 *
 * The remembered release lives in one volatile field, so a restart forgets it and an install before a new check
 * is refused.
 *
 * @param apkByteCap the largest staged file accepted, in bytes.
 */
internal class ReleaseUpdateChecker(
    private val clock: Clock,
    private val auth: AuthService,
    private val ledger: UpdateLedger,
    private val manifests: ManifestSource,
    private val downloads: ApkDownloader,
    private val installer: PackageInstallerPort,
    private val installed: InstalledVersion,
    private val pinnedKeys: List<PinnedKey>,
    private val apkByteCap: Long = MAX_APK_BYTES,
) : UpdateChecker {

    @Volatile
    private var checked: ParsedManifest? = null

    override fun check(force: Boolean): UpdateCheckResult? {
        val now = clock.nowEpochMillis()
        val isAdmin = auth.currentSession()?.isAdmin == true
        val decision = decideUpdateCheck(now, ledger.lastDefinitiveCheckMillis(), ledger.lastAttemptMillis(), force, isAdmin)
        if (decision == ScheduleDecision.NotDue) return null
        ledger.recordAttempt(now)
        return runCheck(now)
    }

    override fun install(release: ReleaseInfo): UpdateInstallResult {
        val now = clock.nowEpochMillis()
        val verified = checked
        if (verified == null || verified.release != release) return UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED)
        val build = installed.current()
        if (!isNewerRelease(build.versionCode, verified.versionCode)) return UpdateInstallResult.Refused(UpdateSentences.NOT_NEWER)
        return stageAndInstall(release, now)
    }

    private fun runCheck(now: Long): UpdateCheckResult {
        val loaded = manifests.load()
        if (loaded !is ManifestLoad.Loaded) return UpdateCheckResult.Failed(UpdateSentences.SERVER_UNREACHABLE)
        val parsed = when (val outcome = ManifestReader.read(loaded.manifestText)) {
            is ManifestOutcome.Accepted -> outcome.manifest
            is ManifestOutcome.Refused -> return UpdateCheckResult.Failed(outcome.reason)
        }
        if (ApkOrigin.refusalFor(loaded.serverBase, parsed.release.apkUrl) != null) {
            return UpdateCheckResult.Failed(UpdateSentences.ORIGIN)
        }
        val verdict = verifyManifestSignature(
            pinnedKeys,
            parsed.keyId,
            parsed.versionCode.toString(),
            parsed.release.sha256,
            parsed.release.signature,
        )
        if (verdict != SignatureVerdict.Verified) return UpdateCheckResult.Failed(UpdateSentences.SIGNATURE)
        checked = parsed
        val build = installed.current()
        ledger.recordDefinitiveCheck(now)
        return if (isNewerRelease(build.versionCode, parsed.versionCode)) {
            UpdateCheckResult.Available(parsed.release)
        } else {
            UpdateCheckResult.UpToDate(build.versionName)
        }
    }

    private fun stageAndInstall(release: ReleaseInfo, now: Long): UpdateInstallResult {
        val download = downloads.download(release.apkUrl)
        if (download !is ApkDownload.Staged) return UpdateInstallResult.Failed(UpdateSentences.DOWNLOAD)
        val apk = download.apk
        try {
            verifyStaged(apk, release.sha256)?.let { return it }
            return installStaged(apk, now)
        } finally {
            apk.discard()
        }
    }

    /** Returns the refusal or failure for a staged file, or null when its digest matches and it is not empty. */
    private fun verifyStaged(apk: StagedApk, sha256: String): UpdateInstallResult? {
        val stream = apk.open() ?: return UpdateInstallResult.Failed(UpdateSentences.OPEN)
        val digest = try {
            verifyStreamDigest(stream, sha256, apkByteCap)
        } finally {
            closeQuietly(stream)
        }
        return when (digest) {
            is StreamDigestResult.Matched -> if (digest.byteCount == 0L) refused(UpdateSentences.EMPTY) else null
            is StreamDigestResult.Mismatched -> refused(if (digest.byteCount == 0L) UpdateSentences.EMPTY else UpdateSentences.DIGEST)
            StreamDigestResult.TooLarge -> refused(UpdateSentences.APK_TOO_LARGE)
            StreamDigestResult.Unreadable -> UpdateInstallResult.Failed(UpdateSentences.READ)
            StreamDigestResult.ExpectedMalformed -> refused(UpdateSentences.DIGEST)
        }
    }

    private fun installStaged(apk: StagedApk, now: Long): UpdateInstallResult =
        when (installer.install(apk)) {
            InstallerResult.Accepted -> {
                ledger.recordUpdate(now)
                UpdateInstallResult.Installed
            }
            InstallerResult.Refused -> UpdateInstallResult.Refused(UpdateSentences.INSTALLER_REFUSED)
            InstallerResult.Failed -> UpdateInstallResult.Failed(UpdateSentences.INSTALLER_FAILED)
        }

    private fun refused(reason: String): UpdateInstallResult = UpdateInstallResult.Refused(reason)

    /** Closes [stream]; only an IOException from the close is ignored, since the digest is already decided. */
    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (error: IOException) {
            Unit
        }
    }
}
