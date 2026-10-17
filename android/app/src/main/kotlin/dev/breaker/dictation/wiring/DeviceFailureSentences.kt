package dev.breaker.dictation.wiring

/**
 * The sentences a take cut by a device failure answers with. Pinned word for word and by length in
 * DeviceFailureSentencesTest; each text is on the strings ledger. The endings are the shared
 * [EndingTexts]; the nothing-heard line is the runner's own.
 */
internal object DeviceFailureSentences {
    const val LEAD: String = "Recording stopped because the microphone failed."
    const val SAVED_AND_COPIED: String = LEAD + " " + EndingTexts.SAVED_AND_COPIED
    const val COPIED_NOT_SAVED: String = LEAD + " " + EndingTexts.COPIED_NOT_SAVED
    const val NOTHING_HEARD: String = LEAD + " " + RunnerSentences.NOTHING_HEARD
    const val NOT_CONVERTED: String = LEAD + " " + EndingTexts.NOT_CONVERTED

    /** The outcome lines of a device failure: a blank text answers the nothing-heard line, and an empty take the lead. */
    val lines: OutcomeLines = OutcomeLines(SAVED_AND_COPIED, COPIED_NOT_SAVED, NOTHING_HEARD, LEAD, NOT_CONVERTED)
}
