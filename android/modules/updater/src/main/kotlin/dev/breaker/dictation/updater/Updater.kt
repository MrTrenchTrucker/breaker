package dev.breaker.dictation.updater

import dev.breaker.dictation.core.port.UpdateChecker
import java.io.File

/** The file name the APK is downloaded to, inside the folder the app gives. */
private const val APK_FILE_NAME = "update.apk"

/**
 * What the app holds of the updater: the checker it calls, the version of the held rollback offer (or
 * null when none is held), and the start and the stop of the daily timer.
 */
public class Updater(
    val checker: UpdateChecker,
    val heldRollbackVersion: () -> String?,
    val start: () -> Unit,
    val stop: () -> Unit,
)

/**
 * Builds the updater from [config] and wires the steps together. Nothing runs here: no check, no
 * download and no timer start.
 */
public fun buildUpdater(config: UpdaterConfig): Updater {
    val held = HeldRollback()
    val fetch = ManifestFetch(config.transport, config.serverUrl)
    val aware = RollbackAwareCheck(fetch, config.installedVersionCode, config.installedVersionName, held)
    val scheduled = ScheduledUpdateCheck(
        runCheck = aware::check,
        lastCheckAtMs = config.times::lastCheckAtMs,
        recordCheckAtMs = config.times::recordCheckAtMs,
        nowMs = config.nowMs,
    )
    val apkDownload = ApkDownload(config.transport, config.serverUrl) {
        File(config.apkFolder(), APK_FILE_NAME)
    }
    val signatureCheck = SignatureCheck(config.pinnedKeys)
    val verification = UpdateVerification(
        download = apkDownload::download,
        checkSignature = signatureCheck::verify,
    )
    val installer = UpdateInstaller(config.installer, config.times::recordUpdateAtMs, config.nowMs)
    val impl = UpdateCheckerImpl(
        runScheduled = { force -> scheduled.check(force) },
        isAdmin = config.isAdmin,
        verifyRelease = { release ->
            verification.verify(release.version, release.apkUrl, release.sha256, release.signature)
        },
        installReady = installer::install,
        held = held,
        onInstallResult = config.onInstallResult,
    )
    val timer = UpdateTimer(runCheck = { scheduled.check(false) }, scheduler = config.scheduler)
    return Updater(
        checker = impl,
        heldRollbackVersion = { held.current()?.version },
        start = timer::start,
        stop = timer::stop,
    )
}
