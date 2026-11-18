package dev.breaker.dictation.updater

/**
 * Launches the system installer for one verified APK file. The Android adapter implements it. It may throw;
 * the caller turns a throw into a refusal, so nothing above this port sees the exception.
 */
public fun interface ApkInstaller {
    fun launch(apk: java.io.File): InstallResult
}
