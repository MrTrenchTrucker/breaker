package dev.breaker.dictation.ui.screen.onboarding

/*
 * Every word the set-up screen shows, in one place.
 *
 * The wording is plain on purpose: short sentences, no jargon, and Android's own menu
 * labels quoted exactly where the user has to find them. The texts are drafts for
 * review, so changing a sentence is an edit to this file and nothing else. The tests
 * check the rules that must hold whatever the words are (plain ASCII, nothing blank,
 * nothing long, none of the words a user must never see).
 *
 * The switch is always called "on" or "off" here.
 */
internal object SetupTexts {
    /** The screen's title. */
    const val TITLE = "Set up Breaker"

    /** What the screen is for. */
    const val INTRO =
        "Breaker types what you say into the app you are using. " +
            "It needs a few things from your phone first. " +
            "Only the microphone is needed to switch Breaker on; " +
            "the tile also needs \"Display over other apps\". " +
            "Go through the steps below, then switch Breaker on."

    /** The state words shown beside each step. */
    const val STATUS_GRANTED = "Done"
    const val STATUS_NOT_GRANTED = "Not done yet"

    /** Step: display over other apps. */
    const val HEADING_OVERLAY = "Display over other apps"
    const val WHY_OVERLAY =
        "Breaker puts a small tile on your screen. You tap the tile to start. " +
            "Android calls this \"Display over other apps\"."

    /** Step: microphone. */
    const val HEADING_MICROPHONE = "Microphone"
    const val WHY_MICROPHONE =
        "Breaker needs the microphone to hear you. It records only when you start it. " +
            "If Android stops asking, allow it in the app info."

    /** Step: notifications, which can be skipped. */
    const val HEADING_NOTIFICATIONS = "Notifications (optional)"
    const val WHY_NOTIFICATIONS =
        "When notices are allowed, Breaker shows one while it is on. You can skip this step."

    /** What the user loses by skipping notifications. */
    const val NOTIFICATIONS_LOST =
        "Without notifications Breaker still works. " +
            "But you see no notice while it is on, " +
            "you cannot switch it off from the notification shade, " +
            "and the notice cannot open your history."

    /** Step: accessibility access. */
    const val HEADING_ACCESSIBILITY = "Accessibility access"
    const val WHY_ACCESSIBILITY =
        "This lets Breaker put your words into the field you are typing in."

    /** The limits Breaker keeps once it has accessibility access. */
    const val ACCESSIBILITY_LIMITS =
        "What Breaker does with this access: it acts only when you send. " +
            "It reads only the one field it types into. " +
            "It never fills a password field. " +
            "It keeps nothing from your screen. " +
            "It sends nothing off the phone."

    /** The extra confirmation Android 13 and newer can ask for. */
    const val RESTRICTED_INTRO =
        "Android 13 and newer can block this access for apps that were not installed from a store. " +
            "If that happens, do these three things in order."
    const val RESTRICTED_STEP_1 =
        "Open accessibility settings. Under \"Installed apps\" or \"Downloaded apps\", tap Breaker. " +
            "If Android says \"Restricted setting\", press OK."
    const val RESTRICTED_STEP_2 =
        "Open Breaker's app info, tap the three dots at the top right, " +
            "and choose \"Allow restricted settings\"."
    const val RESTRICTED_STEP_3 =
        "Go back to accessibility settings and turn on Breaker's accessibility access. " +
            "Android asks you to confirm; press Allow. Then come back here."

    /** The button for each way a step can be opened. */
    const val BUTTON_REQUEST_MICROPHONE = "Allow the microphone"
    const val BUTTON_REQUEST_NOTIFICATIONS = "Allow notifications"
    const val BUTTON_OVERLAY_PAGE = "Open \"Display over other apps\""
    const val BUTTON_NOTIFICATION_PAGE = "Open notification settings"
    const val BUTTON_ACCESSIBILITY_LIST = "Open accessibility settings"
    const val BUTTON_APP_INFO = "Open app info"

    /** The button that reads the phone's answers again. */
    const val BUTTON_CHECK_AGAIN = "Check again"

    /** The switch block. */
    const val SWITCH_HEADING = "Breaker switch"
    const val SWITCH_ON_STATE = "Breaker is on."
    const val SWITCH_OFF_STATE = "Breaker is off."
    const val BUTTON_SWITCH_ON = "Switch Breaker on"
    const val BUTTON_SWITCH_OFF = "Switch Breaker off"
    const val SWITCH_NEEDS_MICROPHONE =
        "Allow the microphone first. Breaker cannot be switched on without it."
    const val SWITCH_NOTE_ONGOING_NOTICE = "When notices are allowed, Breaker shows one while it is on."

    /** What is lost if a step that is not required is left undone. */
    const val WARNING_NO_OVERLAY = "Without \"Display over other apps\" there is no tile on your screen."
    const val WARNING_NO_ACCESSIBILITY =
        "Without accessibility access your words go to the clipboard instead of into the field."

    /** The one-line messages left behind by something that did not work. */
    const val NOTICE_COULD_NOT_CHECK =
        "Breaker could not check your phone just now. What you see may be out of date."
    const val NOTICE_COULD_NOT_OPEN =
        "Breaker could not open that. Open it yourself from your phone's settings."
    const val NOTICE_SWITCH_FAILED = "Breaker could not change the switch. Try again."
    const val NOTICE_NOT_ACCEPTED = "That request was not accepted. Nothing was changed."
}
