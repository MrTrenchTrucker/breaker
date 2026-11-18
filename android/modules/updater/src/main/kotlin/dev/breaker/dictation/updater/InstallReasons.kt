package dev.breaker.dictation.updater

/**
 * The reason sentences of an install that did not start. They are fixed: none holds a path, an address,
 * a version or exception text, so a caller can show or log one as it is.
 */
public object InstallReasons {
    const val NOT_ALLOWED: String = "installing updates is not allowed for Breaker yet"
    const val NOT_STARTED: String = "the installer could not be started"
    const val UNEXPECTED: String = "the install could not finish"
}
