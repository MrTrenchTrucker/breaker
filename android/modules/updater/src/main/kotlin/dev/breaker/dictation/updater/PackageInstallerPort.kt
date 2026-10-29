package dev.breaker.dictation.updater

/** What the system installer answered for one staged file. */
internal enum class InstallerResult {
    /** The installer took the file. */
    Accepted,

    /** The installer refused the file on purpose. */
    Refused,

    /** The installer could not finish. */
    Failed,
}

/**
 * Hands a verified staged file to the system installer.
 *
 * An expected answer is an [InstallerResult]. A port failure that is not an answer may be thrown as a
 * RuntimeException; the checker lets it propagate.
 */
internal interface PackageInstallerPort {
    /** Installs [staged] and returns the installer's answer. */
    fun install(staged: StagedApk): InstallerResult
}
