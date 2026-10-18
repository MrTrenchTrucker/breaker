package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.SetupStep

/*
 * The pages of the first run, in the order the user meets them.
 *
 * The first run is a fixed sequence of full pages that opens before the normal app.
 * Which page comes after which, which pages show a step mark, which page asks for which
 * setup step, which page lists what is still missing and which pages take a swipe are
 * decided here and nowhere else, so they can be checked on a plain JVM with no device.
 */

/** The pages of the first run. */
internal enum class FirstRunPage { WELCOME, MICROPHONE, OVERLAY, NOTIFICATIONS, ACCESSIBILITY, DOWNLOAD }

/** The rules about the pages: their order, their step marks, their setup steps and their swipes. */
internal object FirstRunPages {
    /** The pages in the order the user meets them. */
    val ORDER: List<FirstRunPage> = listOf(
        FirstRunPage.WELCOME,
        FirstRunPage.MICROPHONE,
        FirstRunPage.OVERLAY,
        FirstRunPage.NOTIFICATIONS,
        FirstRunPage.ACCESSIBILITY,
        FirstRunPage.DOWNLOAD,
    )

    /** How many step marks the permission pages show. */
    const val STEP_COUNT: Int = 4

    /** The page after [page] in [ORDER], or null after the last page. */
    fun next(page: FirstRunPage): FirstRunPage? = ORDER.getOrNull(ORDER.indexOf(page) + 1)

    /** The one-based step mark of [page]: 1 to 4 on the permission pages, 0 on the other two. */
    fun stepOf(page: FirstRunPage): Int = when (page) {
        FirstRunPage.MICROPHONE -> 1
        FirstRunPage.OVERLAY -> 2
        FirstRunPage.NOTIFICATIONS -> 3
        FirstRunPage.ACCESSIBILITY -> 4
        FirstRunPage.WELCOME, FirstRunPage.DOWNLOAD -> 0
    }

    /** The setup step a permission page asks for, or null on the welcome page and the download page. */
    fun setupStepOf(page: FirstRunPage): SetupStep? = when (page) {
        FirstRunPage.MICROPHONE -> SetupStep.MICROPHONE
        FirstRunPage.OVERLAY -> SetupStep.OVERLAY
        FirstRunPage.NOTIFICATIONS -> SetupStep.NOTIFICATIONS
        FirstRunPage.ACCESSIBILITY -> SetupStep.ACCESSIBILITY
        FirstRunPage.WELCOME, FirstRunPage.DOWNLOAD -> null
    }

    /** Whether [page] is the last permission page, the one that lists what is still missing. */
    fun isLastPermission(page: FirstRunPage): Boolean = page == FirstRunPage.ACCESSIBILITY

    /** Whether a right-to-left swipe moves [page] on: true on the four permission pages only. */
    fun swipes(page: FirstRunPage): Boolean = setupStepOf(page) != null
}
