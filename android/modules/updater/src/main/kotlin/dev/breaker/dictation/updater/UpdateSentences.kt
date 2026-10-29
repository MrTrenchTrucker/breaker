package dev.breaker.dictation.updater

/**
 * The fixed plain sentences that the checker gives for each outcome. None of them holds an address, a digest,
 * key material or document text.
 */
internal object UpdateSentences {
    const val SERVER_UNREACHABLE: String = "The update server could not be reached."
    const val ORIGIN: String = "The update file does not come from the update server."
    const val SIGNATURE: String = "The update file signature could not be verified."
    const val DOWNLOAD: String = "The update file could not be downloaded."
    const val OPEN: String = "The downloaded update file could not be opened."
    const val READ: String = "The downloaded update file could not be read."
    const val NOT_CHECKED: String = "Check for updates before installing."
    const val NOT_NEWER: String = "This release is not newer than the installed version."
    const val DIGEST: String = "The downloaded update file does not match its digest."
    const val EMPTY: String = "The downloaded update file is empty."
    const val APK_TOO_LARGE: String = "The downloaded update file is larger than the allowed size."
    const val INSTALLER_REFUSED: String = "The system refused to install the update."
    const val INSTALLER_FAILED: String = "The update could not be installed."

    /** Every fixed sentence above, in one list. */
    val ALL: List<String> = listOf(
        SERVER_UNREACHABLE,
        ORIGIN,
        SIGNATURE,
        DOWNLOAD,
        OPEN,
        READ,
        NOT_CHECKED,
        NOT_NEWER,
        DIGEST,
        EMPTY,
        APK_TOO_LARGE,
        INSTALLER_REFUSED,
        INSTALLER_FAILED,
    )
}
