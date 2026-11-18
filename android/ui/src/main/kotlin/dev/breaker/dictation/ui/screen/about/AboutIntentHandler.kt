package dev.breaker.dictation.ui.screen.about

/**
 * Which row taps may act right now.
 *
 * The About view asks before it calls the app. Two things refuse a tap: a check or an install
 * already in progress, and a row the state does not offer. Everything else is the app's to
 * answer, and this object never calls anything itself.
 */
internal object AboutIntentHandler {

    /**
     * True when a tap on [action] may act on [state] now.
     *
     * False while a check or an install is in progress, so a second tap cannot start a second
     * one. Otherwise true only when [decideAbout] offers that row, so a person who is not an
     * admin (offered nothing) cannot act, the install row cannot act once its offer lapsed, and an
     * unknown action is never a row.
     *
     * The rows come from [decideAbout] rather than from a second reading of the flags, so the
     * screen and the tap guard cannot disagree about what is on offer.
     */
    fun accepts(action: String, state: AboutState): Boolean {
        if (state.checking || state.installing) return false
        return action in decideAbout(state).rows
    }
}