package dev.breaker.dictation.updater

/**
 * The reason sentences of a check that could not finish. They are fixed: none holds an address,
 * a status text, a reply body or an exception message, so a caller can show or log one as it is.
 */
internal object CheckReasons {
    const val NO_ADDRESS: String = "no server address is set"
    const val UNREACHABLE: String = "the server could not be reached"
    const val NOT_ALLOWED: String = "the update address is not allowed"
    const val SERVER_ERROR: String = "the server answered with an error"
    const val BAD_ANSWER: String = "the answer was not a valid release"
    const val UNUSABLE: String = "the offered release cannot be used"
    const val UNFINISHED: String = "the check could not finish"
}
