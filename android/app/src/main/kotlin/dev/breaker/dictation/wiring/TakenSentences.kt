package dev.breaker.dictation.wiring

/** The words of a take the microphone took away. Pinned word for word; a changed letter is a red test, not a typo. */
internal object TakenSentences {
    const val LEAD: String = "Recording stopped for a call or another app."
    const val SAVED_AND_COPIED: String = LEAD + " " + EndingTexts.SAVED_AND_COPIED
    const val TAKEN_NOTHING_HEARD: String = LEAD + " " + RunnerSentences.NOTHING_HEARD
    const val NOT_CONVERTED: String = LEAD + " " + EndingTexts.NOT_CONVERTED
    const val COPIED_NOT_SAVED: String = LEAD + " " + EndingTexts.COPIED_NOT_SAVED
    const val STILL_TAKEN: String =
        "Another app still has the microphone. Tap the tile again when it is free."
}
