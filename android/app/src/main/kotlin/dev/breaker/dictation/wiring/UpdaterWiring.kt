package dev.breaker.dictation.wiring

import dev.breaker.dictation.auth.client.AddressedSession
import dev.breaker.dictation.http.HttpTransport
import dev.breaker.dictation.updater.ApkInstaller
import dev.breaker.dictation.updater.InstallResult
import dev.breaker.dictation.updater.TimerScheduler
import dev.breaker.dictation.updater.UpdateTimes
import dev.breaker.dictation.updater.Updater
import dev.breaker.dictation.updater.UpdaterConfig
import dev.breaker.dictation.updater.buildUpdater
import java.io.File

/**
 * Builds the updater from the app's values. Every value is passed through by name to the updater's
 * config with no change, so the app decides the values and the updater decides the behaviour.
 *
 * This file names no Android type, so a JVM test can build it with fakes.
 */
internal fun buildAppUpdater(
    transport: HttpTransport,
    serverUrl: () -> String,
    apkFolder: () -> File,
    pinnedKeys: List<ByteArray>,
    isAdmin: () -> Boolean,
    installedVersionCode: () -> Long,
    installedVersionName: () -> String,
    installer: ApkInstaller,
    times: UpdateTimes,
    nowMs: () -> Long,
    scheduler: TimerScheduler,
    onInstallResult: (InstallResult) -> Unit,
): Updater =
    buildUpdater(
        UpdaterConfig(
            transport = transport,
            serverUrl = serverUrl,
            apkFolder = apkFolder,
            pinnedKeys = pinnedKeys,
            isAdmin = isAdmin,
            installedVersionCode = installedVersionCode,
            installedVersionName = installedVersionName,
            installer = installer,
            times = times,
            nowMs = nowMs,
            scheduler = scheduler,
            onInstallResult = onInstallResult,
        ),
    )

/**
 * True only when [session] gives a session and that session belongs to an admin. A missing session is
 * not an admin, and an exception from [session] counts as not an admin.
 */
internal fun appIsAdmin(session: () -> AddressedSession?): Boolean =
    try {
        val addressed: AddressedSession? = session()
        addressed != null && addressed.session.isAdmin
    } catch (e: Exception) {
        false
    }
