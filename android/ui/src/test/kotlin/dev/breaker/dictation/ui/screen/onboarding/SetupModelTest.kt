package dev.breaker.dictation.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules behind the set-up screen: what each step reports, which button it offers, which
 * step is next, when the switch can be turned on and which warnings sit beside it. Every
 * expected value is written out here, apart from the code, and every rule is checked on the
 * Android versions either side of each boundary.
 */
class SetupModelTest {
    private val sdks = listOf(29, 30, 33, 36)

    private fun phone(
        overlay: Boolean = false,
        microphone: Boolean = false,
        notifications: Boolean = false,
        accessibility: Boolean = false,
        sdkInt: Int = 36,
    ) = PlatformStatus(overlay, microphone, notifications, accessibility, sdkInt)

    private fun state(
        status: PlatformStatus = phone(),
        switchedOn: Boolean = false,
        micAskedBefore: Boolean = false,
        notificationsAskedBefore: Boolean = false,
    ) = SetupState(status, switchedOn, micAskedBefore, notificationsAskedBefore)

    private val allGranted = phone(overlay = true, microphone = true, notifications = true, accessibility = true)

    /** Each status with exactly one answer granted, named by the step it grants. */
    private val onlyOne = listOf(
        SetupStep.OVERLAY to phone(overlay = true),
        SetupStep.MICROPHONE to phone(microphone = true),
        SetupStep.NOTIFICATIONS to phone(notifications = true),
        SetupStep.ACCESSIBILITY to phone(accessibility = true),
    )

    /** Each status with exactly one answer missing, named by the step it lacks. */
    private val allButOne = listOf(
        SetupStep.OVERLAY to allGranted.copy(overlay = false),
        SetupStep.MICROPHONE to allGranted.copy(microphone = false),
        SetupStep.NOTIFICATIONS to allGranted.copy(notifications = false),
        SetupStep.ACCESSIBILITY to allGranted.copy(accessibility = false),
    )

    @Test
    fun `the steps and the other names are the ones the screen is written against`() {
        val steps = listOf(SetupStep.OVERLAY, SetupStep.MICROPHONE, SetupStep.NOTIFICATIONS, SetupStep.ACCESSIBILITY)
        assertEquals(steps, SetupStep.entries)
        assertEquals(listOf(StepStatus.GRANTED, StepStatus.NOT_GRANTED), StepStatus.entries)
        assertEquals(
            setOf("REQUEST_MICROPHONE", "REQUEST_NOTIFICATIONS", "OVERLAY_PAGE", "NOTIFICATION_PAGE", "ACCESSIBILITY_LIST", "APP_INFO"),
            OpenAction.entries.map { it.name }.toSet(),
        )
        assertEquals(6, OpenAction.entries.size)
        assertEquals(listOf(OpenResult.OPENED, OpenResult.NOT_POSSIBLE), OpenResult.entries)
        assertEquals(listOf(SetupWarning.NO_OVERLAY, SetupWarning.NO_ACCESSIBILITY), SetupWarning.entries)
    }

    @Test
    fun `the status carries its five answers in the order they are given`() {
        val read = PlatformStatus(true, false, true, false, 7)
        assertEquals(listOf<Any>(true, false, true, false, 7), listOf(read.overlay, read.microphone, read.notifications, read.accessibility, read.sdkInt))
        val other = PlatformStatus(false, true, false, true, 8)
        assertEquals(listOf<Any>(false, true, false, true, 8), listOf(other.overlay, other.microphone, other.notifications, other.accessibility, other.sdkInt))
    }

    @Test
    fun `the notifications flag may be left out and then reads false`() {
        val short = SetupState(allGranted, switchedOn = false, micAskedBefore = true)
        assertEquals(false, short.notificationsAskedBefore)
        assertEquals(SetupState(allGranted, false, true, false), short)
    }

    @Test
    fun `a step is granted exactly when its own answer is granted`() {
        for (sdk in sdks) {
            for ((granted, answers) in onlyOne) {
                for (probed in SetupStep.entries) {
                    val expected = if (probed == granted) StepStatus.GRANTED else StepStatus.NOT_GRANTED
                    assertEquals("only $granted granted, $probed asked, sdk $sdk", expected, state(answers.copy(sdkInt = sdk)).statusOf(probed))
                }
            }
            for ((missing, answers) in allButOne) {
                for (probed in SetupStep.entries) {
                    val expected = if (probed == missing) StepStatus.NOT_GRANTED else StepStatus.GRANTED
                    assertEquals("only $missing missing, $probed asked, sdk $sdk", expected, state(answers.copy(sdkInt = sdk)).statusOf(probed))
                }
            }
        }
    }

    @Test
    fun `a granted step offers no button, whatever was asked before`() {
        for (sdk in sdks) {
            for (micAsked in listOf(false, true)) {
                for (notificationsAsked in listOf(false, true)) {
                    val granted = state(allGranted.copy(sdkInt = sdk), micAskedBefore = micAsked, notificationsAskedBefore = notificationsAsked)
                    for (step in SetupStep.entries) {
                        assertNull("$step, sdk $sdk, mic asked $micAsked, notifications asked $notificationsAsked", granted.actionFor(step))
                    }
                }
            }
        }
    }

    @Test
    fun `only the step that is granted loses its button`() {
        for (sdk in sdks) {
            for ((granted, answers) in onlyOne) {
                for (probed in SetupStep.entries) {
                    val action = state(answers.copy(sdkInt = sdk)).actionFor(probed)
                    assertEquals("only $granted granted, $probed asked, sdk $sdk", probed != granted, action != null)
                }
            }
        }
    }

    @Test
    fun `the overlay step opens the overlay page and the accessibility step opens the accessibility list`() {
        for (sdk in sdks) {
            for (asked in listOf(false, true)) {
                val missing = state(phone(sdkInt = sdk), micAskedBefore = asked, notificationsAskedBefore = asked)
                assertEquals("overlay, sdk $sdk, asked $asked", OpenAction.OVERLAY_PAGE, missing.actionFor(SetupStep.OVERLAY))
                assertEquals("accessibility, sdk $sdk, asked $asked", OpenAction.ACCESSIBILITY_LIST, missing.actionFor(SetupStep.ACCESSIBILITY))
            }
        }
    }

    @Test
    fun `the microphone is asked for the first time and then sent to the app info`() {
        for (sdk in sdks) {
            val first = state(phone(sdkInt = sdk), micAskedBefore = false)
            val again = state(phone(sdkInt = sdk), micAskedBefore = true)
            assertEquals("first ask, sdk $sdk", OpenAction.REQUEST_MICROPHONE, first.actionFor(SetupStep.MICROPHONE))
            assertEquals("asked before, sdk $sdk", OpenAction.APP_INFO, again.actionFor(SetupStep.MICROPHONE))
        }
    }

    @Test
    fun `notifications are prompted for from Android 13 on the first time and sent to the settings page otherwise`() {
        class Row(val sdk: Int, val asked: Boolean, val expected: OpenAction)
        val page = OpenAction.NOTIFICATION_PAGE
        val prompt = OpenAction.REQUEST_NOTIFICATIONS
        val rows = listOf(
            Row(29, false, page), Row(29, true, page), Row(30, false, page), Row(30, true, page),
            Row(32, false, page), Row(32, true, page), Row(33, false, prompt), Row(33, true, page),
            Row(34, false, prompt), Row(36, false, prompt), Row(36, true, page),
        )
        for (row in rows) {
            val shown = state(phone(sdkInt = row.sdk), notificationsAskedBefore = row.asked)
            assertEquals("sdk ${row.sdk}, asked ${row.asked}", row.expected, shown.actionFor(SetupStep.NOTIFICATIONS))
        }
    }

    @Test
    fun `the microphone memory and the notifications memory do not touch each other`() {
        val micAsked = state(phone(sdkInt = 36), micAskedBefore = true, notificationsAskedBefore = false)
        assertEquals(OpenAction.REQUEST_NOTIFICATIONS, micAsked.actionFor(SetupStep.NOTIFICATIONS))
        assertEquals(OpenAction.APP_INFO, micAsked.actionFor(SetupStep.MICROPHONE))
        val notificationsAsked = state(phone(sdkInt = 36), micAskedBefore = false, notificationsAskedBefore = true)
        assertEquals(OpenAction.REQUEST_MICROPHONE, notificationsAsked.actionFor(SetupStep.MICROPHONE))
        assertEquals(OpenAction.NOTIFICATION_PAGE, notificationsAsked.actionFor(SetupStep.NOTIFICATIONS))
    }

    @Test
    fun `only the microphone decides whether the switch can be turned on`() {
        for (sdk in sdks) {
            for (switchedOn in listOf(false, true)) {
                for ((granted, answers) in onlyOne) {
                    assertEquals(
                        "only $granted granted, sdk $sdk, on $switchedOn",
                        granted == SetupStep.MICROPHONE,
                        state(answers.copy(sdkInt = sdk), switchedOn = switchedOn).canSwitchOn,
                    )
                }
                for ((missing, answers) in allButOne) {
                    assertEquals(
                        "only $missing missing, sdk $sdk, on $switchedOn",
                        missing != SetupStep.MICROPHONE,
                        state(answers.copy(sdkInt = sdk), switchedOn = switchedOn).canSwitchOn,
                    )
                }
            }
        }
    }

    @Test
    fun `the restricted help is shown from Android 13 on while accessibility is not on`() {
        class Row(val sdk: Int, val accessibility: Boolean, val expected: Boolean)
        val rows = listOf(
            Row(29, false, false), Row(30, false, false), Row(32, false, false),
            Row(33, false, true), Row(34, false, true), Row(36, false, true),
            Row(29, true, false), Row(32, true, false), Row(33, true, false), Row(36, true, false),
        )
        for (row in rows) {
            for (others in listOf(false, true)) {
                val answers = phone(
                    overlay = others, microphone = others, notifications = others,
                    accessibility = row.accessibility, sdkInt = row.sdk,
                )
                assertEquals(
                    "sdk ${row.sdk}, accessibility ${row.accessibility}, other steps granted $others",
                    row.expected,
                    state(answers).showRestrictedHelp,
                )
            }
        }
    }

    @Test
    fun `the next step is the first one in display order that is not granted`() {
        class Row(val answers: PlatformStatus, val expected: SetupStep?)
        val rows = listOf(
            Row(phone(), SetupStep.OVERLAY),
            Row(phone(overlay = true), SetupStep.MICROPHONE),
            Row(phone(overlay = true, microphone = true), SetupStep.NOTIFICATIONS),
            Row(phone(overlay = true, microphone = true, notifications = true), SetupStep.ACCESSIBILITY),
            Row(allGranted, null),
            Row(allGranted.copy(overlay = false), SetupStep.OVERLAY),
            Row(allGranted.copy(microphone = false), SetupStep.MICROPHONE),
            Row(allGranted.copy(notifications = false), SetupStep.NOTIFICATIONS),
            Row(allGranted.copy(microphone = false, accessibility = false), SetupStep.MICROPHONE),
            Row(phone(overlay = true, notifications = true), SetupStep.MICROPHONE),
            Row(phone(microphone = true, notifications = true, accessibility = true), SetupStep.OVERLAY),
            Row(phone(accessibility = true), SetupStep.OVERLAY),
        )
        for (row in rows) {
            for (sdk in sdks) {
                assertEquals("answers ${row.answers}, sdk $sdk", row.expected, state(row.answers.copy(sdkInt = sdk)).nextStep)
            }
        }
    }

    @Test
    fun `the warnings list what is missing in a fixed order while the switch matters`() {
        class Row(val answers: PlatformStatus, val switchedOn: Boolean, val expected: List<SetupWarning>)
        val microphoneOnly = phone(microphone = true)
        val rows = listOf(
            Row(microphoneOnly, false, listOf(SetupWarning.NO_OVERLAY, SetupWarning.NO_ACCESSIBILITY)),
            Row(microphoneOnly, true, listOf(SetupWarning.NO_OVERLAY, SetupWarning.NO_ACCESSIBILITY)),
            Row(allGranted, false, emptyList()),
            Row(allGranted, true, emptyList()),
            Row(allGranted.copy(overlay = false), false, listOf(SetupWarning.NO_OVERLAY)),
            Row(allGranted.copy(accessibility = false), false, listOf(SetupWarning.NO_ACCESSIBILITY)),
            Row(allGranted.copy(notifications = false), false, emptyList()),
            Row(allGranted.copy(overlay = false, notifications = false), false, listOf(SetupWarning.NO_OVERLAY)),
            Row(allGranted.copy(accessibility = false, notifications = false), true, listOf(SetupWarning.NO_ACCESSIBILITY)),
            Row(allGranted.copy(overlay = false, accessibility = false), true, listOf(SetupWarning.NO_OVERLAY, SetupWarning.NO_ACCESSIBILITY)),
            // Without the microphone the switch cannot be turned on, so there is nothing to warn about yet ...
            Row(phone(), false, emptyList()),
            Row(allGranted.copy(microphone = false), false, emptyList()),
            // ... unless it is on already, as it can be when the microphone was taken away afterwards.
            Row(phone(), true, listOf(SetupWarning.NO_OVERLAY, SetupWarning.NO_ACCESSIBILITY)),
            Row(allGranted.copy(microphone = false), true, emptyList()),
        )
        for (row in rows) {
            for (sdk in sdks) {
                assertEquals(
                    "answers ${row.answers}, on ${row.switchedOn}, sdk $sdk",
                    row.expected,
                    state(row.answers.copy(sdkInt = sdk), switchedOn = row.switchedOn).warnings,
                )
            }
        }
    }

    @Test
    fun `missing notifications never produce a warning, because Breaker works without them`() {
        for (sdk in sdks) {
            for (switchedOn in listOf(false, true)) {
                val shown = state(allGranted.copy(notifications = false, sdkInt = sdk), switchedOn = switchedOn)
                assertEquals("sdk $sdk, on $switchedOn", emptyList<SetupWarning>(), shown.warnings)
            }
        }
    }

    @Test
    fun `whether the switch is on alters no step and no button`() {
        for (sdk in sdks) {
            for ((_, answers) in onlyOne + allButOne) {
                val off = state(answers.copy(sdkInt = sdk), switchedOn = false)
                val on = state(answers.copy(sdkInt = sdk), switchedOn = true)
                for (step in SetupStep.entries) {
                    assertEquals("$step, sdk $sdk", off.statusOf(step), on.statusOf(step))
                    assertEquals("$step, sdk $sdk", off.actionFor(step), on.actionFor(step))
                }
                assertEquals(listOf(off.nextStep, off.canSwitchOn, off.showRestrictedHelp), listOf(on.nextStep, on.canSwitchOn, on.showRestrictedHelp))
            }
        }
    }
}
