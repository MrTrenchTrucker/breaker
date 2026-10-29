package dev.breaker.dictation.updater

/** What the system installer answered for one staged file. */
internal enum class InstallerResult {
    /** The installer took the file. */
    Accepted,

    /** The installer refused the file on purpose. */
    Refused,

    /** The installer could not finish. */
    Failed,

    /** The bytes copied into the installer did not match the checked digest, or they went past the cap. Nothing was installed. */
    DigestMismatch,
}

/**
 * Hands a verified staged file to the system installer.
 *
 * An expected answer is an [InstallerResult]. A port failure that is not an answer may be thrown as a
 * RuntimeException; the checker lets it propagate.
 */
internal interface PackageInstallerPort {
    /**
     * Installs [staged] and returns the installer's answer. The bytes are checked again while they are copied
     * into the installer session: [expectedSha256] is the digest the checker verified, and [capBytes] is the largest
     * size accepted. A mismatch or an overrun is [InstallerResult.DigestMismatch], and nothing is installed.
     */
    fun install(staged: StagedApk, expectedSha256: String, capBytes: Long): InstallerResult
}
