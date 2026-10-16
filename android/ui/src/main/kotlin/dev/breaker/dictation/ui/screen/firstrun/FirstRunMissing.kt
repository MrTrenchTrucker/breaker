package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.SetupState

/*
 * What the last permission page lists as still missing, and the button each item offers.
 *
 * The list follows the order of the permission pages, not the order of the setup steps.
 * Each button is the answer the setup rules give, so it is the same button the setup screen
 * would offer: a microphone prompt that was already asked opens the app's page, and a granted
 * permission has no item at all.
 */

/** One item of the missing list: the permission page it belongs to and the button it offers. */
internal data class MissingItem(val page: FirstRunPage, val action: OpenAction)

/** The missing list of the last permission page. */
internal object FirstRunMissing {
    /**
     * The items for every permission that is not granted, in the first-run order: microphone,
     * overlay, notifications, accessibility.
     */
    fun of(state: SetupState): List<MissingItem> =
        FirstRunPages.ORDER.mapNotNull { page ->
            val step = FirstRunPages.setupStepOf(page) ?: return@mapNotNull null
            state.actionFor(step)?.let { action -> MissingItem(page, action) }
        }
}
