package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The missing list of the last permission page: what is listed, in which order, and which button each
 * item offers. The buttons are the setup rules' own answers, so each is checked against the rule it follows.
 */
class FirstRunMissingTest {
    private fun phone(
        overlay: Boolean = true,
        microphone: Boolean = true,
        notifications: Boolean = true,
        accessibility: Boolean = true,
        sdkInt: Int = 36,
        rationale: Boolean = false,
    ) = PlatformStatus(overlay, microphone, notifications, accessibility, sdkInt, rationale)

    private fun state(
        status: PlatformStatus,
        micAsked: Boolean = false,
        notificationsAsked: Boolean = false,
    ) = SetupState(
        status = status,
        switchedOn = false,
        micAskedBefore = micAsked,
        notificationsAskedBefore = notificationsAsked,
    )

    @Test
    fun `nothing missing gives an empty list`() {
        assertEquals(
            "ui: first run: a fully granted phone listed items",
            emptyList<MissingItem>(),
            FirstRunMissing.of(state(phone())),
        )
    }

    @Test
    fun `only the overlay missing gives one overlay item with the overlay page action`() {
        assertEquals(
            "ui: first run: only the overlay missing gave the wrong items",
            listOf(MissingItem(FirstRunPage.OVERLAY, OpenAction.OVERLAY_PAGE)),
            FirstRunMissing.of(state(phone(overlay = false))),
        )
    }

    @Test
    fun `all four missing give four items in first-run order with the actions the setup rules give`() {
        val missing = phone(overlay = false, microphone = false, notifications = false, accessibility = false)
        assertEquals(
            "ui: first run: four missing did not give four items in first-run order with the setup actions",
            listOf(
                MissingItem(FirstRunPage.MICROPHONE, OpenAction.REQUEST_MICROPHONE),
                MissingItem(FirstRunPage.OVERLAY, OpenAction.OVERLAY_PAGE),
                MissingItem(FirstRunPage.NOTIFICATIONS, OpenAction.REQUEST_NOTIFICATIONS),
                MissingItem(FirstRunPage.ACCESSIBILITY, OpenAction.ACCESSIBILITY_LIST),
            ),
            FirstRunMissing.of(state(missing)),
        )
    }

    @Test
    fun `the microphone item asks first and opens the app page once the prompt was asked before`() {
        assertEquals(
            "ui: first run: a microphone never asked did not offer the prompt",
            OpenAction.REQUEST_MICROPHONE,
            FirstRunMissing.of(state(phone(microphone = false))).single().action,
        )
        assertEquals(
            "ui: first run: an asked microphone did not open the app page",
            OpenAction.APP_INFO,
            FirstRunMissing.of(state(phone(microphone = false), micAsked = true)).single().action,
        )
        assertEquals(
            "ui: first run: the system rationale did not bring back the prompt",
            OpenAction.REQUEST_MICROPHONE,
            FirstRunMissing.of(state(phone(microphone = false, rationale = true), micAsked = true)).single().action,
        )
    }

    @Test
    fun `the notification item asks from Android 13 on and opens the settings page otherwise`() {
        assertEquals(
            "ui: first run: Android 13 did not ask for notifications",
            OpenAction.REQUEST_NOTIFICATIONS,
            FirstRunMissing.of(state(phone(notifications = false, sdkInt = 33))).single().action,
        )
        assertEquals(
            "ui: first run: an asked notification prompt did not open the settings page",
            OpenAction.NOTIFICATION_PAGE,
            FirstRunMissing.of(state(phone(notifications = false, sdkInt = 33), notificationsAsked = true)).single().action,
        )
        assertEquals(
            "ui: first run: Android 12 asked for notifications",
            OpenAction.NOTIFICATION_PAGE,
            FirstRunMissing.of(state(phone(notifications = false, sdkInt = 32))).single().action,
        )
    }

    @Test
    fun `a missing list never includes the welcome or the download page`() {
        val missing = phone(overlay = false, microphone = false, notifications = false, accessibility = false)
        assertEquals(
            "ui: first run: the missing list named a page with no permission",
            emptyList<FirstRunPage>(),
            FirstRunMissing.of(state(missing)).map { it.page }.filter { it == FirstRunPage.WELCOME || it == FirstRunPage.DOWNLOAD },
        )
    }
}
