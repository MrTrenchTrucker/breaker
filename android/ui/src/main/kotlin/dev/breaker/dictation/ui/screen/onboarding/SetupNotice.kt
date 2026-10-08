package dev.breaker.dictation.ui.screen.onboarding

/*
 * The messages the set-up screen can leave behind.
 *
 * A notice says only what happened, in a fixed sentence from SetupTexts. It is
 * never built from the text of a caught exception, which could carry anything. The
 * one message that is not fixed here is the app's own sentence when it refuses to
 * switch Breaker on: that sentence is written by the app for this purpose and is
 * shown as given.
 *
 * A notice belongs to one drawing of the screen. The handler passes it on once and
 * does not keep it.
 */

/** Why the last action on the set-up screen did not do what was asked, as a sentence for the user. */
internal sealed class SetupNotice(val text: String) {
    /** The phone's answers or the switch could not be read, so the screen may be out of date. */
    data object COULD_NOT_CHECK : SetupNotice(SetupTexts.NOTICE_COULD_NOT_CHECK)

    /** The permission prompt or settings page did not open. */
    data object COULD_NOT_OPEN : SetupNotice(SetupTexts.NOTICE_COULD_NOT_OPEN)

    /** Switching Breaker on or off did not work. */
    data object SWITCH_FAILED : SetupNotice(SetupTexts.NOTICE_SWITCH_FAILED)

    /** The request names something this screen does not offer. */
    data object NOT_ACCEPTED : SetupNotice(SetupTexts.NOTICE_NOT_ACCEPTED)

    /** The app refused to switch Breaker on, in its own words. */
    data class Refused(val sentence: String) : SetupNotice(sentence)
}
