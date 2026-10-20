package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.controls.ControlTexts
import dev.breaker.dictation.ui.screen.history.HistoryTexts
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.screen.settings.SettingsScreen

/**
 * The words the home screen shows, in plain ASCII.
 *
 * Every sentence the user reads on that screen is one of these. The only other
 * text drawn is the time of a row, which is written by the phone's own formatter,
 * and the transcriptions themselves.
 */
internal object HomeTexts {

    /** The lettering over the big mic. */
    const val TAP_TO_TALK = "Tap to talk"

    /** The line the two menu items without a screen answer. */
    const val MENU_NOT_BUILT = "Not available yet."

    /** The model row's not-ready value. */
    const val STATUS_MODEL_MISSING = "Not downloaded"

    /** The server row's word while no server is set. */
    const val STATUS_NOT_SET = "Not set"

    /** The server row's word when it cannot be reached. */
    const val STATUS_NOT_AVAILABLE = "Not available"

    val BUTTON_SHOW_ALL: String get() = HistoryTexts.BUTTON_SHOW_ALL
    val BUTTON_HIDE: String get() = HistoryTexts.BUTTON_HIDE

    const val MENU_HOW_IT_WORKS = "How it works" // home_menu_how_it_works
    const val MENU_ABOUT = "About" // home_menu_about
    const val MENU_BUTTON = "Menu" // home_menu_button
    const val TAP_HINT = "Your words go straight to the clipboard." // home_tap_hint
    const val LAST_TRANSCRIPT = "Last transcript" // home_last_transcript
    const val LOGO = "Breaker" // home_logo
    const val STATUS_LAST_DICTATION = "Last dictation" // home_last_dictation

    val MENU_HISTORY: String get() = HistoryTexts.TITLE
    val MENU_SETTINGS: String get() = SettingsScreen.TITLE
    val BUTTON_COPY: String get() = HistoryTexts.BUTTON_COPY
    val EMPTY_BOX: String get() = HistoryTexts.EMPTY
    val TAG_SERVER: String get() = HistoryTexts.TAG_SERVER
    val HEADING_MODEL: String get() = SetupTexts.HEADING_MODEL
    val SWITCH_ON: String get() = SetupTexts.SWITCH_ON_STATE
    val SWITCH_OFF: String get() = SetupTexts.SWITCH_OFF_STATE
    val STATUS_MODEL_READY: String get() = ControlTexts.BADGE_READY

    /** The cancel action on the recording mic. */
    const val CANCEL = "Cancel"

    /** The done action on the recording mic. */
    const val DONE = "Done"
}
