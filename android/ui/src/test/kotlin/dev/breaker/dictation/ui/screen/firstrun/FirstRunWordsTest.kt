package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupState
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The words of the first-run pages. Each one must be the set-up text it names, and the
 * pages that have no permission must have no heading and no reason.
 */
class FirstRunWordsTest {
    /** A phone state with only the given answers, on the given Android version. */
    private fun state(
        overlay: Boolean = false,
        microphone: Boolean = false,
        notifications: Boolean = false,
        accessibility: Boolean = false,
        sdkInt: Int = 36,
    ) = SetupState(
        status = PlatformStatus(overlay, microphone, notifications, accessibility, sdkInt),
        switchedOn = false,
        micAskedBefore = false,
    )

    @Test
    fun `each permission page names its own heading and reason from the set-up texts`() {
        assertEquals(SetupTexts.HEADING_MICROPHONE, FirstRunWords.heading(FirstRunPage.MICROPHONE))
        assertEquals(SetupTexts.HEADING_OVERLAY, FirstRunWords.heading(FirstRunPage.OVERLAY))
        assertEquals(SetupTexts.HEADING_NOTIFICATIONS, FirstRunWords.heading(FirstRunPage.NOTIFICATIONS))
        assertEquals(SetupTexts.HEADING_ACCESSIBILITY, FirstRunWords.heading(FirstRunPage.ACCESSIBILITY))
        assertEquals(SetupTexts.WHY_MICROPHONE, FirstRunWords.why(FirstRunPage.MICROPHONE))
        assertEquals(SetupTexts.WHY_OVERLAY, FirstRunWords.why(FirstRunPage.OVERLAY))
        assertEquals(SetupTexts.WHY_NOTIFICATIONS, FirstRunWords.why(FirstRunPage.NOTIFICATIONS))
        assertEquals(SetupTexts.WHY_ACCESSIBILITY, FirstRunWords.why(FirstRunPage.ACCESSIBILITY))
    }

    @Test
    fun `the welcome page and the download page have no heading and no reason`() {
        for (page in listOf(FirstRunPage.WELCOME, FirstRunPage.DOWNLOAD)) {
            assertNull("heading of $page", FirstRunWords.heading(page))
            assertNull("reason of $page", FirstRunWords.why(page))
        }
    }

    @Test
    fun `each open action has the button text the set-up texts name for it`() {
        assertEquals(SetupTexts.BUTTON_REQUEST_MICROPHONE, FirstRunWords.buttonFor(OpenAction.REQUEST_MICROPHONE))
        assertEquals(SetupTexts.BUTTON_REQUEST_NOTIFICATIONS, FirstRunWords.buttonFor(OpenAction.REQUEST_NOTIFICATIONS))
        assertEquals(SetupTexts.BUTTON_OVERLAY_PAGE, FirstRunWords.buttonFor(OpenAction.OVERLAY_PAGE))
        assertEquals(SetupTexts.BUTTON_NOTIFICATION_PAGE, FirstRunWords.buttonFor(OpenAction.NOTIFICATION_PAGE))
        assertEquals(SetupTexts.BUTTON_ACCESSIBILITY_LIST, FirstRunWords.buttonFor(OpenAction.ACCESSIBILITY_LIST))
        assertEquals(SetupTexts.BUTTON_APP_INFO, FirstRunWords.buttonFor(OpenAction.APP_INFO))
    }

    @Test
    fun `every open action has a button text of its own`() {
        val texts = OpenAction.entries.map { FirstRunWords.buttonFor(it) }.toSet()
        assertEquals("button texts: $texts", OpenAction.entries.size, texts.size)
    }

    @Test
    fun `the status word is the granted word when granted and the not granted word otherwise`() {
        assertEquals(SetupTexts.STATUS_GRANTED, FirstRunWords.status(true))
        assertEquals(SetupTexts.STATUS_NOT_GRANTED, FirstRunWords.status(false))
    }

    @Test
    fun `the accessibility page on Android 13 with accessibility not granted gives the limits then the three steps`() {
        assertEquals(
            listOf(
                SetupTexts.ACCESSIBILITY_LIMITS,
                SetupTexts.RESTRICTED_INTRO,
                SetupTexts.RESTRICTED_STEP_1,
                SetupTexts.RESTRICTED_STEP_2,
                SetupTexts.RESTRICTED_STEP_3,
            ),
            FirstRunWords.extras(FirstRunPage.ACCESSIBILITY, state(sdkInt = 33)),
        )
    }

    @Test
    fun `the accessibility page gives only the limits when accessibility is granted or Android is below 13`() {
        assertEquals(
            listOf(SetupTexts.ACCESSIBILITY_LIMITS),
            FirstRunWords.extras(FirstRunPage.ACCESSIBILITY, state(accessibility = true, sdkInt = 33)),
        )
        assertEquals(
            listOf(SetupTexts.ACCESSIBILITY_LIMITS),
            FirstRunWords.extras(FirstRunPage.ACCESSIBILITY, state(sdkInt = 32)),
        )
    }

    @Test
    fun `the notifications page gives the lost line only while notifications are not granted`() {
        assertEquals(
            listOf(SetupTexts.NOTIFICATIONS_LOST),
            FirstRunWords.extras(FirstRunPage.NOTIFICATIONS, state()),
        )
        assertEquals(
            emptyList<String>(),
            FirstRunWords.extras(FirstRunPage.NOTIFICATIONS, state(notifications = true)),
        )
    }

    @Test
    fun `the other pages give no extra lines`() {
        for (page in listOf(FirstRunPage.WELCOME, FirstRunPage.MICROPHONE, FirstRunPage.OVERLAY, FirstRunPage.DOWNLOAD)) {
            assertEquals("extras of $page", emptyList<String>(), FirstRunWords.extras(page, state(sdkInt = 33)))
        }
    }

    @Test
    fun `the model words are the model step's own texts and the source line carries the size`() {
        assertEquals(SetupTexts.WHY_MODEL, FirstRunWords.modelWhy())
        assertEquals(SetupTexts.BUTTON_DOWNLOAD_MODEL, FirstRunWords.modelButton())
        val line = FirstRunWords.modelSource(365)
        assertTrue("the size is missing from: $line", line.contains("365"))
        assertFalse("the placeholder is left in: $line", line.contains("{size}"))
        assertEquals(SetupTexts.MODEL_STEP_LINE.replace("{size}", "365"), line)
    }

    @Test
    fun `the keyboard tile page names its heading and reason from the set-up texts`() {
        assertEquals(SetupTexts.HEADING_KEYBOARD_TILE, FirstRunWords.heading(FirstRunPage.KEYBOARD_TILE))
        assertEquals(SetupTexts.WHY_KEYBOARD_TILE, FirstRunWords.why(FirstRunPage.KEYBOARD_TILE))
    }

    @Test
    fun `the keyboard tile page gives its privacy line as its one extra line`() {
        assertEquals(listOf(SetupTexts.KEYBOARD_TILE_LIMITS), FirstRunWords.extras(FirstRunPage.KEYBOARD_TILE, state()))
        assertEquals(listOf(SetupTexts.KEYBOARD_TILE_LIMITS), FirstRunWords.extras(FirstRunPage.KEYBOARD_TILE, state(accessibility = true)))
    }
}
