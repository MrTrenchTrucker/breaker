package dev.breaker.dictation.ui.screen.about

/**
 * What the About screen shows for a state: the rows that may be tapped, and one status sentence.
 *
 * [rows] is a subset of [AboutActions]; [sentence] is null when there is nothing to say. Which
 * sentence wins is a fixed order, not an accident of which flag is set: an install in progress
 * says so before a check does, a failure before an offer, an offer before "up to date".
 */
internal data class AboutDecision(
    val rows: Set<String>,
    val sentence: String?,
)

/**
 * Decides the rows and the sentence for [state].
 *
 * A person who is not an admin gets nothing at all: no rows and no sentence, which is the
 * two-line screen as it stood before the rows existed. For an admin the check row is always
 * offered, and the install row only while an update is available and the app can install it. The
 * sentence is the first of these that applies, in this order: installing, checking, failed, update
 * available, up to date. When none applies there is no sentence.
 */
internal fun decideAbout(state: AboutState): AboutDecision {
    if (!state.admin) return AboutDecision(emptySet(), null)
    val rows = buildSet {
        add(AboutActions.CHECK)
        if (state.updateAvailable && state.installReady) add(AboutActions.INSTALL)
    }
    val sentence = when {
        state.installing -> AboutTexts.INSTALLING
        state.checking -> AboutTexts.CHECKING
        state.failed -> AboutTexts.FAILED
        state.updateAvailable -> AboutTexts.OFFERED
        state.upToDate -> AboutTexts.UP_TO_DATE
        else -> null
    }
    return AboutDecision(rows, sentence)
}