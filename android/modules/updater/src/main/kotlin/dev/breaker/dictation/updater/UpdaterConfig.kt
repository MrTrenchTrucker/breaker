package dev.breaker.dictation.updater

import dev.breaker.dictation.http.HttpTransport
import java.io.File

/**
 * Everything the updater needs from the app, passed in as values and functions, so that this module
 * names no Android class and no app store. [buildUpdater] wires them together.
 *
 * [serverUrl] and [apkFolder] are read when they are needed, not copied. [installedVersionCode] and
 * [installedVersionName] are read on every check. [isAdmin] is read on every forced check.
 */
public class UpdaterConfig(
    val transport: HttpTransport,
    val serverUrl: () -> String,
    val apkFolder: () -> File,
    val pinnedKeys: List<ByteArray>,
    val isAdmin: () -> Boolean,
    val installedVersionCode: () -> Long,
    val installedVersionName: () -> String,
    val installer: ApkInstaller,
    val times: UpdateTimes,
    val nowMs: () -> Long,
    val scheduler: TimerScheduler,
    val onInstallResult: (InstallResult) -> Unit,
)
