package dev.breaker.dictation.updater

/** Fixed refusal sentences for the APK download step. None holds a URL, host, digest, file name, or exception text. */
internal object ApkDownloadReasons {
    const val BAD_DIGEST = "the offered digest is not usable"
    const val NOT_SAME_ORIGIN = "the update address is not the configured server"
    const val NOT_ALLOWED = "the update address is not allowed"
    const val BAD_STATUS = "the server answered with an error"
    const val REPLY_TOO_LARGE = "the reply is larger than its cap"
    const val LENGTH_MISMATCH = "the body ended before its announced length"
    const val UNREACHABLE = "the server could not be reached"
    const val UNFINISHED = "the download could not finish"
    const val BAD_DESTINATION = "the destination is not usable"
}
