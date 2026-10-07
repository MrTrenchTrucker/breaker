package dev.breaker.dictation.stt.ondevice

/**
 * Receives the technical text of a model problem: file paths, exception class
 * and message, digests. That text must never reach a user.
 *
 * It never receives transcript text or audio. The app wires it to the platform
 * log later; this module has no Android log dependency, so the sink is the seam.
 */
fun interface ModelDebugSink {
    /** Records [message] for a developer. [message] is not shown to a user. */
    fun debug(message: String)
}

/** The default sink: drops every message. */
object NoDebugSink : ModelDebugSink {
    override fun debug(message: String) {}
}

/**
 * The sentences a user may see about a model. Each names the class of problem
 * in plain words; none carries a path, an exception text or a digest.
 */
object ModelMessages {
    /** The requested model id is not in the registry. */
    const val MODEL_UNKNOWN = "That model is not available."

    /** The registry entry belongs to a model family this engine cannot run. */
    const val MODEL_WRONG_FAMILY = "The on-device engine cannot run that model."

    /** No archive is stored for the model. */
    const val MODEL_NOT_INSTALLED = "The model is not installed. Download it first."

    /** The checksum list needed for the check was unavailable, so nothing was judged. */
    const val NOT_VERIFIED_YET = "The model could not be checked right now."

    /** The archive failed its check and was removed. */
    const val CHECK_FAILED_DELETED = "The model file failed its check and was deleted. Download it again."

    /** The archive failed its check and could not be removed, so it is still on the phone. */
    const val CHECK_FAILED_NOT_DELETED = "The model file failed its check but could not be deleted."

    /** The archive passed its check but the engine could not start with it. */
    const val ENGINE_COULD_NOT_START = "The on-device engine could not start with this model."

    /** The verified archive could not be written to the phone's storage. */
    const val SAVE_FAILED = "The model could not be saved on the phone."

    /** The upstream checksum list could not be fetched. */
    const val CHECKSUMS_DOWNLOAD_FAILED = "The checksum list could not be downloaded."

    /** The checksum list arrived but could not be parsed. */
    const val CHECKSUMS_UNREADABLE_DOWNLOADED = "The downloaded checksum list could not be read."

    /** The model archive could not be fetched. */
    const val MODEL_DOWNLOAD_FAILED = "The model could not be downloaded."

    /** The downloaded archive failed its check and was thrown away. */
    const val DOWNLOAD_FAILED_CHECK = "The downloaded file failed its check and was discarded. Try again."

    /** The downloaded archive failed its check and could not be removed, so it is still on the phone. */
    const val DOWNLOAD_FAILED_CHECK_NOT_DELETED = "The downloaded file failed its check but could not be discarded."

    /** Appended after another sentence when removing the bad file also failed. */
    const val COULD_NOT_DELETE = " The file could not be deleted."
}
