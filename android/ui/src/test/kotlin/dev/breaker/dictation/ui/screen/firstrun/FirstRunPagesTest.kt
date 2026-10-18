package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The order of the first-run pages and the rules that hang off each page: the page after it, its
 * step mark, its setup step, the last permission page and the swipe. Every expected value is
 * written out here, apart from the code.
 */
class FirstRunPagesTest {
    private val permissions = listOf(
        FirstRunPage.MICROPHONE,
        FirstRunPage.OVERLAY,
        FirstRunPage.NOTIFICATIONS,
        FirstRunPage.ACCESSIBILITY,
    )

    @Test
    fun `the pages come in the design order and there are exactly six`() {
        assertEquals(
            "ui: first run: the page order is not the six pages in the design order",
            listOf(
                FirstRunPage.WELCOME,
                FirstRunPage.MICROPHONE,
                FirstRunPage.OVERLAY,
                FirstRunPage.NOTIFICATIONS,
                FirstRunPage.ACCESSIBILITY,
                FirstRunPage.DOWNLOAD,
            ),
            FirstRunPages.ORDER,
        )
        assertEquals("ui: first run: the page enum does not hold six entries", 6, FirstRunPage.entries.size)
    }

    @Test
    fun `next walks the order and is null after the download page`() {
        assertEquals("ui: first run: the page after the welcome is wrong", FirstRunPage.MICROPHONE, FirstRunPages.next(FirstRunPage.WELCOME))
        assertEquals("ui: first run: the page after the microphone is wrong", FirstRunPage.OVERLAY, FirstRunPages.next(FirstRunPage.MICROPHONE))
        assertEquals("ui: first run: the page after the overlay is wrong", FirstRunPage.NOTIFICATIONS, FirstRunPages.next(FirstRunPage.OVERLAY))
        assertEquals("ui: first run: the page after the notifications is wrong", FirstRunPage.ACCESSIBILITY, FirstRunPages.next(FirstRunPage.NOTIFICATIONS))
        assertEquals("ui: first run: the page after the accessibility page is wrong", FirstRunPage.DOWNLOAD, FirstRunPages.next(FirstRunPage.ACCESSIBILITY))
        assertNull("ui: first run: the page after the download page is not null", FirstRunPages.next(FirstRunPage.DOWNLOAD))
        val walked = generateSequence(FirstRunPage.WELCOME) { FirstRunPages.next(it) }.toList()
        assertEquals("ui: first run: next does not walk the whole order", FirstRunPages.ORDER, walked)
    }

    @Test
    fun `the step count is four and the step marks are one to four on the permission pages`() {
        assertEquals("ui: first run: the step count is not four", 4, FirstRunPages.STEP_COUNT)
        assertEquals("ui: first run: the step marks are not one to four", listOf(1, 2, 3, 4), permissions.map { FirstRunPages.stepOf(it) })
        assertEquals("ui: first run: the welcome page has a step mark", 0, FirstRunPages.stepOf(FirstRunPage.WELCOME))
        assertEquals("ui: first run: the download page has a step mark", 0, FirstRunPages.stepOf(FirstRunPage.DOWNLOAD))
    }

    @Test
    fun `each permission page maps to its setup step and the other two map to nothing`() {
        assertEquals(
            "ui: first run: the permission pages do not map to their setup steps",
            listOf(SetupStep.MICROPHONE, SetupStep.OVERLAY, SetupStep.NOTIFICATIONS, SetupStep.ACCESSIBILITY),
            permissions.map { FirstRunPages.setupStepOf(it) },
        )
        assertNull("ui: first run: the welcome page maps to a setup step", FirstRunPages.setupStepOf(FirstRunPage.WELCOME))
        assertNull("ui: first run: the download page maps to a setup step", FirstRunPages.setupStepOf(FirstRunPage.DOWNLOAD))
    }

    @Test
    fun `only the accessibility page is the last permission page`() {
        assertEquals(
            "ui: first run: the last permission page is not the accessibility page alone",
            listOf(FirstRunPage.ACCESSIBILITY),
            FirstRunPage.entries.filter { FirstRunPages.isLastPermission(it) },
        )
    }

    @Test
    fun `only the four permission pages swipe`() {
        assertEquals(
            "ui: first run: the swiping pages are not the four permission pages",
            permissions,
            FirstRunPage.entries.filter { FirstRunPages.swipes(it) },
        )
    }
}
