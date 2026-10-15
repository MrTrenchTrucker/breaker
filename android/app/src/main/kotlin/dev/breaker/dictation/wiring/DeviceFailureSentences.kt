package dev.breaker.dictation.wiring

/**
 * The sentences a take cut by a device failure answers with. Pinned word for word and by length in
 * DeviceFailureSentencesTest; each text is on the strings ledger.
 */
internal object DeviceFailureSentences {
    const val LEAD: String = "Recording stopped because the microphone failed."
    const val SAVED_AND_COPIED: String = "Recording stopped because the microphone failed. Text is in History and copied."
    const val COPIED_NOT_SAVED: String = "Recording stopped because the microphone failed. Text copied, but not saved to History."
    const val NOTHING_HEARD: String = "Recording stopped because the microphone failed. Nothing was heard."
    const val NOT_CONVERTED: String = "Recording stopped because the microphone failed. The speech could not be converted."

    /** The outcome lines of a device failure: a blank text answers the nothing-heard line, and an empty take the lead. */
    val lines: OutcomeLines = OutcomeLines(SAVED_AND_COPIED, COPIED_NOT_SAVED, NOTHING_HEARD, LEAD, NOT_CONVERTED)
}
