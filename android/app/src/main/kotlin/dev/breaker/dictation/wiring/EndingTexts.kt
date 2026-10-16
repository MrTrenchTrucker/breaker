package dev.breaker.dictation.wiring

/**
 * The shared endings of a settled take, one constant per outcome, so every branch of the app
 * answers the same words. The taken and device-failure branches prepend their own lead; the Home
 * screen answers the ending on its own. There is no NOTHING_HEARD row here: that ending already
 * exists as [RunnerSentences.NOTHING_HEARD].
 */
internal object EndingTexts {
    /** The text was saved to History and copied to the clipboard. */
    const val SAVED_AND_COPIED: String = "Text is in History and copied."

    /** The text was copied to the clipboard, but the save to History failed. */
    const val COPIED_NOT_SAVED: String = "Text copied, but not saved to History."

    /** The engine could not convert the speech. */
    const val NOT_CONVERTED: String = "The speech could not be converted."
}
