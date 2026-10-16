package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.KitLook

/*
 * The look of the Next button on a first-run page.
 *
 * The granted status is already re-read on attach, window focus and resume by FirstRunController
 * (refresh). This object turns that one reading into the right kit look for the Next button: grey
 * while the page's own step is still missing, green once it is granted. Nothing here touches a
 * device or the phone; it only shapes what the render layer passes to the buttons.
 */
internal object FirstRunNext {

    /** Whether [shown]'s own permission step has already been granted. */
    fun ready(shown: FirstRunShown): Boolean = shown.missing.none { it.page == shown.page }

    /**
     * The kit look for the Next button on [shown]: RAISED once the page's own step is granted,
     * NEUTRAL while that step is still missing. The look follows the current grant as read in shown.
     */
    fun look(shown: FirstRunShown): KitLook = when {
        ready(shown) -> KitLook.RAISED
        else -> KitLook.NEUTRAL
    }
}
