package dev.breaker.dictation.updater

/** The build that is running now: the integer code that decides a newer release, and the name shown to the user. */
internal data class InstalledBuild(val versionCode: Int, val versionName: String)

/** Reads the build that is running now. */
internal interface InstalledVersion {
    /** The running build. The name is shown to the user and is never compared. */
    fun current(): InstalledBuild
}
