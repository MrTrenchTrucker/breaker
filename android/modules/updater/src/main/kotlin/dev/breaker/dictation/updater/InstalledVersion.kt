package dev.breaker.dictation.updater

/** The build that is running now: the integer code that decides a newer release, and the name shown to the user. */
internal data class InstalledBuild(val versionCode: Int, val versionName: String)

/** The result of reading the running build: a build that was read, or a reading that failed. */
internal sealed class InstalledReading {
    /** The running build was read. */
    internal data class Known(val build: InstalledBuild) : InstalledReading()

    /** The running build could not be read. Nothing is compared or remembered. */
    internal object Unreadable : InstalledReading()
}

/** Reads the build that is running now. */
internal interface InstalledVersion {
    /** The running build, or the reason it could not be read. The name is shown to the user and is never compared. */
    fun current(): InstalledReading
}
