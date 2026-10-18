package dev.breaker.dictation.wiring

/**
 * The lines the Home take answers with. The `app` module cannot read a `ui` `internal` object, so
 * the Home lines live here: the two APPROVED answer lines, and the outcome lines of a settled
 * take: the shared [EndingTexts] endings, with the runner's own nothing-heard line for a blank
 * and for an empty take. Nothing is re-spelled: every ending is an existing constant.
 */
internal object HomeSentences {
    /** The switch is off; the tap answers and turns nothing on. */
    const val SWITCHED_OFF: String = "Breaker is off. Turn it on to talk."

    /** Another app still holds the microphone; the tile keeps its own STILL_TAKEN. */
    const val MIC_TAKEN: String = "Another app still has the microphone. Try again when it is free."

    /** The outcome lines of a settled Home take, from the shared endings and the runner's blank. */
    val lines: OutcomeLines = OutcomeLines(
        EndingTexts.SAVED_AND_COPIED,
        EndingTexts.COPIED_NOT_SAVED,
        RunnerSentences.NOTHING_HEARD,
        RunnerSentences.NOTHING_HEARD,
        EndingTexts.NOT_CONVERTED,
    )
}
