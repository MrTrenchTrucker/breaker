package dev.breaker.dictation.wiring

/** The words of a take the microphone took away. Pinned word for word; a changed letter is a red test, not a typo. */
internal object TakenSentences {
    const val LEAD: String = "Recording stopped for a call or another app."
    const val SAVED_AND_COPIED: String =
        "Recording stopped for a call or another app. Text is in History and copied."
    const val TAKEN_NOTHING_HEARD: String =
        "Recording stopped for a call or another app. Nothing was heard."
    const val NOT_CONVERTED: String =
        "Recording stopped for a call or another app. The speech could not be converted."
    const val STILL_TAKEN: String =
        "Another app still has the microphone. Breaker listens again once it is free."
}
