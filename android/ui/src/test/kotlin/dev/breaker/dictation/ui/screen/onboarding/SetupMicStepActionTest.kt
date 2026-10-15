package dev.breaker.dictation.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the microphone button offers, given whether the microphone prompt was asked before and
 * whether the system would still show its dialog. The rationale answer is the phone's own
 * question, and it decides only the microphone step.
 */
class SetupMicStepActionTest {
    private fun phone(
        overlay: Boolean = false,
        microphone: Boolean = false,
        notifications: Boolean = false,
        accessibility: Boolean = false,
        microphoneRationale: Boolean = false,
        sdkInt: Int = 36,
    ) = PlatformStatus(
        overlay = overlay,
        microphone = microphone,
        notifications = notifications,
        accessibility = accessibility,
        sdkInt = sdkInt,
        microphoneRationale = microphoneRationale,
    )

    private fun state(
        status: PlatformStatus,
        micAskedBefore: Boolean,
        notificationsAskedBefore: Boolean = false,
    ) = SetupState(
        status = status,
        switchedOn = false,
        micAskedBefore = micAskedBefore,
        notificationsAskedBefore = notificationsAskedBefore,
    )

    @Test
    fun `never asked and no rationale asks with the system dialog`() {
        val action = state(phone(microphoneRationale = false), micAskedBefore = false).actionFor(SetupStep.MICROPHONE)
        assertEquals(OpenAction.REQUEST_MICROPHONE, action)
    }

    @Test
    fun `asked and no rationale opens the app settings page`() {
        val action = state(phone(microphoneRationale = false), micAskedBefore = true).actionFor(SetupStep.MICROPHONE)
        assertEquals(OpenAction.APP_INFO, action)
    }

    @Test
    fun `asked and rationale true asks with the system dialog again`() {
        val action = state(phone(microphoneRationale = true), micAskedBefore = true).actionFor(SetupStep.MICROPHONE)
        assertEquals(OpenAction.REQUEST_MICROPHONE, action)
    }

    @Test
    fun `never asked and rationale true still asks with the system dialog`() {
        val action = state(phone(microphoneRationale = true), micAskedBefore = false).actionFor(SetupStep.MICROPHONE)
        assertEquals(OpenAction.REQUEST_MICROPHONE, action)
    }

    @Test
    fun `a granted microphone offers no button whatever the flags say`() {
        for (asked in listOf(false, true)) {
            for (rationale in listOf(false, true)) {
                val granted = phone(microphone = true, microphoneRationale = rationale)
                assertNull(
                    "asked $asked, rationale $rationale",
                    state(granted, micAskedBefore = asked).actionFor(SetupStep.MICROPHONE),
                )
            }
        }
    }

    @Test
    fun `the rationale answer changes nothing for the other steps`() {
        for (sdk in listOf(29, 33, 36)) {
            for (asked in listOf(false, true)) {
                for (notificationsAsked in listOf(false, true)) {
                    val without = state(
                        phone(sdkInt = sdk, microphoneRationale = false),
                        micAskedBefore = asked,
                        notificationsAskedBefore = notificationsAsked,
                    )
                    val with = state(
                        phone(sdkInt = sdk, microphoneRationale = true),
                        micAskedBefore = asked,
                        notificationsAskedBefore = notificationsAsked,
                    )
                    val steps = listOf(SetupStep.OVERLAY, SetupStep.NOTIFICATIONS, SetupStep.ACCESSIBILITY)
                    for (step in steps) {
                        assertEquals(
                            "$step, sdk $sdk, asked $asked, notifications asked $notificationsAsked",
                            without.actionFor(step),
                            with.actionFor(step),
                        )
                    }
                    assertEquals(
                        "next step, sdk $sdk, asked $asked, notifications asked $notificationsAsked",
                        without.nextStep,
                        with.nextStep,
                    )
                }
            }
        }
    }

    @Test
    fun `the microphone string asks when it was never asked`() {
        val action = SetupActions.openActionFor(
            SetupActions.OPEN_MICROPHONE,
            state(phone(microphoneRationale = false), micAskedBefore = false),
        )
        assertEquals(OpenAction.REQUEST_MICROPHONE, action)
    }

    @Test
    fun `the microphone string opens the app settings page when it was asked and the system would not show the dialog`() {
        val action = SetupActions.openActionFor(
            SetupActions.OPEN_MICROPHONE,
            state(phone(microphoneRationale = false), micAskedBefore = true),
        )
        assertEquals(OpenAction.APP_INFO, action)
    }

    @Test
    fun `the microphone string asks again when it was asked and the system would still show the dialog`() {
        val action = SetupActions.openActionFor(
            SetupActions.OPEN_MICROPHONE,
            state(phone(microphoneRationale = true), micAskedBefore = true),
        )
        assertEquals(OpenAction.REQUEST_MICROPHONE, action)
    }

    @Test
    fun `the microphone string maps to nothing once the microphone is granted`() {
        for (asked in listOf(false, true)) {
            val granted = state(phone(microphone = true), micAskedBefore = asked)
            assertNull("asked $asked", SetupActions.openActionFor(SetupActions.OPEN_MICROPHONE, granted))
        }
    }
}
