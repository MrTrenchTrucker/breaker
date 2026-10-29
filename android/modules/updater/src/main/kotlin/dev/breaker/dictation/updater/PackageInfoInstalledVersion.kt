package dev.breaker.dictation.updater

import android.content.Context

/** Reads the build that is installed now from the package manager of this app. A failed read is Unreadable. */
internal class PackageInfoInstalledVersion(private val context: Context) : InstalledVersion {
    override fun current(): InstalledReading {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (error: Exception) {
            return InstalledReading.Unreadable
        }
        return installedReadingFrom(info.longVersionCode, info.versionName)
    }
}
