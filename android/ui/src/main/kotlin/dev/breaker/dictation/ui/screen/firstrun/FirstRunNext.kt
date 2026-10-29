package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.onboarding.StepStatus

/*
 * The look of the Next button on a first-run page.
 *
 * The granted status is already re-read on attach, window focus and resume by FirstRunController
 * (refresh). This object turns that one reading into the right kit look for the Next button: grey
 * while the page's own step is still missing, green once it is granted. Nothing here touches a
 * device or the phone; it only shapes what the render layer passes to the buttons.
 */
internal object FirstRunNext {

    /**
     * Whether the setup step of [shown]'s page has already been granted. A page with no setup
     * step is always ready.
     */
    fun ready(shown: FirstRunShown): Boolean {
        val step = FirstRunPages.setupStepOf(shown.page) ?: return true
        return shown.state.statusOf(step) == StepStatus.GRANTED
    }

    /**
     * The kit look for the Next button on [shown]: RAISED once the page's own step is granted,
     * NEUTRAL while that step is still missing. The look follows the current grant as read in shown.
     */
    fun look(shown: FirstRunShown): KitLook = when {
        ready(shown) -> KitLook.RAISED
        else -> KitLook.NEUTRAL
    }
}
