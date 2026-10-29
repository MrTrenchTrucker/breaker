package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.SetupState
import dev.breaker.dictation.ui.screen.onboarding.SetupStep
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.screen.onboarding.StepStatus

/*
 * The words the first-run pages show, picked from the set-up texts.
 *
 * This object writes no sentence of its own. Every word it returns is one of the
 * constants in SetupTexts, chosen for the page or the button it belongs to, so the
 * wording stays in one place. Which page gets which words is decided here and nowhere
 * else, so it can be checked on a plain JVM with no device.
 */

/** The words of the first-run pages, picked from the set-up texts. */
internal object FirstRunWords {
    /** The heading of a permission page, or null on the welcome page and the download page. */
    fun heading(page: FirstRunPage): String? = when (page) {
        FirstRunPage.MICROPHONE -> SetupTexts.HEADING_MICROPHONE
        FirstRunPage.OVERLAY -> SetupTexts.HEADING_OVERLAY
        FirstRunPage.NOTIFICATIONS -> SetupTexts.HEADING_NOTIFICATIONS
        FirstRunPage.ACCESSIBILITY -> SetupTexts.HEADING_ACCESSIBILITY
        FirstRunPage.KEYBOARD_TILE -> SetupTexts.HEADING_KEYBOARD_TILE
        FirstRunPage.WELCOME, FirstRunPage.DOWNLOAD -> null
    }

    /** The short reason shown under a permission page's heading, or null on the welcome page and the download page. */
    fun why(page: FirstRunPage): String? = when (page) {
        FirstRunPage.MICROPHONE -> SetupTexts.WHY_MICROPHONE
        FirstRunPage.OVERLAY -> SetupTexts.WHY_OVERLAY
        FirstRunPage.NOTIFICATIONS -> SetupTexts.WHY_NOTIFICATIONS
        FirstRunPage.ACCESSIBILITY -> SetupTexts.WHY_ACCESSIBILITY
        FirstRunPage.KEYBOARD_TILE -> SetupTexts.WHY_KEYBOARD_TILE
        FirstRunPage.WELCOME, FirstRunPage.DOWNLOAD -> null
    }

    /** The text of the one button for [action]. */
    fun buttonFor(action: OpenAction): String = when (action) {
        OpenAction.REQUEST_MICROPHONE -> SetupTexts.BUTTON_REQUEST_MICROPHONE
        OpenAction.REQUEST_NOTIFICATIONS -> SetupTexts.BUTTON_REQUEST_NOTIFICATIONS
        OpenAction.OVERLAY_PAGE -> SetupTexts.BUTTON_OVERLAY_PAGE
        OpenAction.NOTIFICATION_PAGE -> SetupTexts.BUTTON_NOTIFICATION_PAGE
        OpenAction.ACCESSIBILITY_LIST -> SetupTexts.BUTTON_ACCESSIBILITY_LIST
        OpenAction.APP_INFO -> SetupTexts.BUTTON_APP_INFO
    }

    /** The state word beside a step: the granted word when [granted], the not granted word otherwise. */
    fun status(granted: Boolean): String =
        if (granted) SetupTexts.STATUS_GRANTED else SetupTexts.STATUS_NOT_GRANTED

    /**
     * The extra lines of [page] below its reason.
     *
     * The accessibility page lists the limits Breaker keeps, and on Android 13 and newer,
     * while accessibility is not on, the three steps of the restricted-setting help. The
     * notifications page lists what is lost without them, for as long as they are not
     * granted. The keyboard tile page lists its privacy line. Every other page has no extra lines.
     */
    fun extras(page: FirstRunPage, state: SetupState): List<String> = when (page) {
        FirstRunPage.ACCESSIBILITY -> buildList {
            add(SetupTexts.ACCESSIBILITY_LIMITS)
            if (state.showRestrictedHelp) {
                add(SetupTexts.RESTRICTED_INTRO)
                add(SetupTexts.RESTRICTED_STEP_1)
                add(SetupTexts.RESTRICTED_STEP_2)
                add(SetupTexts.RESTRICTED_STEP_3)
            }
        }
        FirstRunPage.NOTIFICATIONS ->
            if (state.statusOf(SetupStep.NOTIFICATIONS) == StepStatus.NOT_GRANTED) {
                listOf(SetupTexts.NOTIFICATIONS_LOST)
            } else {
                emptyList()
            }
        FirstRunPage.KEYBOARD_TILE -> listOf(SetupTexts.KEYBOARD_TILE_LIMITS)
        FirstRunPage.WELCOME, FirstRunPage.MICROPHONE, FirstRunPage.OVERLAY, FirstRunPage.DOWNLOAD -> emptyList()
    }

    /** The reason shown on the model page. */
    fun modelWhy(): String = SetupTexts.WHY_MODEL

    /** The source line under the model button, with the size in decimal megabytes put in place of its placeholder. */
    fun modelSource(sizeMb: Int): String = SetupTexts.MODEL_STEP_LINE.replace("{size}", sizeMb.toString())

    /** The text of the model page's download button. */
    fun modelButton(): String = SetupTexts.BUTTON_DOWNLOAD_MODEL
}
