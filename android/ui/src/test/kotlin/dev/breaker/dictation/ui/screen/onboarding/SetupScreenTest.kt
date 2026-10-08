package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TrimStripe
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the set-up screen draws for a state: the blocks and their order, the one button
 * each step offers and the string it reports, the switch and its warnings, and that every
 * word comes from [SetupTexts] and every colour is one of the slots the screen may use.
 *
 * What a tap does is in SetupIntentHandlerTest; which button a state offers is decided by
 * the model and tested in SetupModelTest, so the buttons here are checked against the
 * strings the screen must report for it.
 */
class SetupScreenTest {
    private val screen = SetupScreen()

    private fun status(
        overlay: Boolean = false,
        microphone: Boolean = false,
        notifications: Boolean = false,
        accessibility: Boolean = false,
        sdkInt: Int = 36,
    ) = PlatformStatus(overlay, microphone, notifications, accessibility, sdkInt)

    private fun state(
        phone: PlatformStatus = status(),
        on: Boolean = false,
        micAsked: Boolean = false,
        notificationsAsked: Boolean = false,
    ) = SetupState(phone, on, micAsked, notificationsAsked)

    private val allGranted = status(overlay = true, microphone = true, notifications = true, accessibility = true)

    private fun render(shown: SetupState = state(), notice: SetupNotice? = null): Screen = screen.render(shown, notice)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun node(s: Screen, id: String): Node? = nodesOf(s).firstOrNull { it.id == id }

    private fun label(s: Screen, id: String): Label =
        node(s, id) as? Label ?: throw AssertionError("no Label with id $id in ${nodesOf(s).map { it.id }}")

    private fun action(s: Screen, id: String): Action =
        node(s, id) as? Action ?: throw AssertionError("no Action with id $id in ${nodesOf(s).map { it.id }}")

    private fun setupAction(a: Action): String =
        (a.intent as? ScreenIntent.Setup)?.action ?: throw AssertionError("action ${a.id} reports ${a.intent}")

    /** A spread of states: nothing granted, everything granted, old and new versions, switch on and off, prompts already shown. */
    private val spread: List<SetupState> = listOf(
        state(),
        state(allGranted),
        state(allGranted, on = true),
        state(status(sdkInt = 29), micAsked = true),
        state(status(sdkInt = 33, microphone = true), on = true, notificationsAsked = true),
        state(status(overlay = true, accessibility = true, sdkInt = 32)),
        state(status(microphone = true, accessibility = true, sdkInt = 30), micAsked = true, notificationsAsked = true),
    )

    @Test
    fun `the screen has its id, title and intro, and every id is unique and in the setup namespace`() {
        val s = render()
        assertEquals("setup", s.id)
        assertEquals(SetupTexts.TITLE, s.title)
        assertEquals(SetupTexts.TITLE, label(s, "setup.title").text)
        assertEquals(SetupTexts.INTRO, label(s, "setup.intro").text)
        for (st in spread) {
            val ids = nodesOf(render(st, SetupNotice.COULD_NOT_OPEN)).map { it.id }
            assertEquals("duplicate ids in $ids", ids.size, ids.toSet().size)
            assertEquals("ids outside the setup namespace", emptyList<String>(), ids.filterNot { it.startsWith("setup.") })
        }
    }

    @Test
    fun `the blocks come in display order and the notice is the first node after the stripe`() {
        val without = render(state(allGranted)).nodes.map { it.id }
        val order = listOf("title", "stripe", "intro", "step.overlay", "step.microphone", "step.notifications")
        assertEquals(order.map { "setup.$it" } + listOf("setup.step.accessibility", "setup.switch", "setup.recheck"), without)
        val withNotice = render(state(allGranted), SetupNotice.NOT_ACCEPTED).nodes.map { it.id }
        assertEquals(without.take(2) + "setup.notice" + without.drop(2), withNotice)
        assertEquals("the notice is drawn straight after the stripe", "setup.notice", withNotice[2])
        assertTrue("the stripe is a trim stripe", render().nodes[1] is TrimStripe)
    }

    @Test
    fun `a step block holds its heading, reason and status in that order`() {
        val s = render()
        val expected = mapOf(
            "overlay" to (SetupTexts.HEADING_OVERLAY to SetupTexts.WHY_OVERLAY),
            "microphone" to (SetupTexts.HEADING_MICROPHONE to SetupTexts.WHY_MICROPHONE),
            "notifications" to (SetupTexts.HEADING_NOTIFICATIONS to SetupTexts.WHY_NOTIFICATIONS),
            "accessibility" to (SetupTexts.HEADING_ACCESSIBILITY to SetupTexts.WHY_ACCESSIBILITY),
        )
        for ((name, texts) in expected) {
            val block = node(s, "setup.step.$name") as Box
            val prefix = "setup.step.$name"
            assertEquals(listOf("$prefix.heading", "$prefix.why", "$prefix.status"), block.children.take(3).map { it.id })
            assertEquals(texts.first, label(s, "$prefix.heading").text)
            assertEquals(texts.second, label(s, "$prefix.why").text)
        }
    }

    @Test
    fun `the accessibility step reads heading, reason, status, limits, restricted help, then the button`() {
        val prefix = "setup.step.accessibility"
        val head = listOf("$prefix.heading", "$prefix.why", "$prefix.status", "$prefix.limits")
        fun idsOf(phone: PlatformStatus): List<String> = (node(render(state(phone)), prefix) as Box).children.map { it.id }
        assertEquals("not granted, with the restricted help", head + "$prefix.restricted" + "$prefix.button", idsOf(status(sdkInt = 36)))
        assertEquals("not granted, before version 33", head + "$prefix.button", idsOf(status(sdkInt = 32)))
        assertEquals("granted", head, idsOf(status(accessibility = true, sdkInt = 36)))
    }

    @Test
    fun `the limits and the restricted help come before the accessibility button in every state with that button`() {
        val prefix = "setup.step.accessibility"
        for (sdk in listOf(29, 30, 33, 36)) {
            val ids = (node(render(state(status(sdkInt = sdk))), prefix) as Box).children.map { it.id }
            val button = ids.indexOf("$prefix.button")
            assertTrue("sdk $sdk: no button in $ids", button >= 0)
            assertTrue("sdk $sdk: the limits must come before the button: $ids", ids.indexOf("$prefix.limits") in 0 until button)
            if (sdk >= 33) assertTrue("sdk $sdk: the restricted help must come before the button: $ids", ids.indexOf("$prefix.restricted") in 0 until button)
        }
    }

    @Test
    fun `each step says done in the sent colour or not done in the warning colour on its own answer`() {
        val names = listOf("overlay", "microphone", "notifications", "accessibility")
        for (granted in names) {
            val only = status(
                overlay = granted == "overlay",
                microphone = granted == "microphone",
                notifications = granted == "notifications",
                accessibility = granted == "accessibility",
            )
            val s = render(state(only))
            for (name in names) {
                val shown = label(s, "setup.step.$name.status")
                val done = name == granted
                assertEquals("$name with only $granted granted", if (done) SetupTexts.STATUS_GRANTED else SetupTexts.STATUS_NOT_GRANTED, shown.text)
                assertEquals(if (done) PaletteSlot.STATE_SENT else PaletteSlot.STATE_WARNING, shown.color)
            }
        }
    }

    /** One row of the button table: the state, the step, and what its button must be, or null for no button. */
    private class ButtonRow(val state: SetupState, val step: String, val string: String?, val text: String?)

    @Test
    fun `a button exists only for a step that is not granted and reports the string for that step and state`() {
        val rows = listOf(
            ButtonRow(state(status()), "overlay", "open.overlay", SetupTexts.BUTTON_OVERLAY_PAGE),
            ButtonRow(state(status(overlay = true)), "overlay", null, null),
            ButtonRow(state(status()), "microphone", "open.microphone", SetupTexts.BUTTON_REQUEST_MICROPHONE),
            ButtonRow(state(status(), micAsked = true), "microphone", "open.appinfo", SetupTexts.BUTTON_APP_INFO),
            ButtonRow(state(status(microphone = true)), "microphone", null, null),
            ButtonRow(state(status(microphone = true), micAsked = true), "microphone", null, null),
            ButtonRow(state(status(sdkInt = 36)), "notifications", "open.notifications", SetupTexts.BUTTON_REQUEST_NOTIFICATIONS),
            ButtonRow(
                state(status(sdkInt = 36), notificationsAsked = true),
                "notifications",
                "open.notifications",
                SetupTexts.BUTTON_NOTIFICATION_PAGE,
            ),
            ButtonRow(state(status(sdkInt = 29)), "notifications", "open.notifications", SetupTexts.BUTTON_NOTIFICATION_PAGE),
            ButtonRow(state(status(notifications = true)), "notifications", null, null),
            ButtonRow(state(status()), "accessibility", "open.accessibility", SetupTexts.BUTTON_ACCESSIBILITY_LIST),
            ButtonRow(state(status(accessibility = true)), "accessibility", null, null),
        )
        for (row in rows) {
            val s = render(row.state)
            val found = node(s, "setup.step.${row.step}.button")
            val why = "step ${row.step}, state ${row.state}"
            if (row.string == null) {
                assertNull("a granted step must have no button: $why", found)
            } else {
                val button = found as? Action ?: throw AssertionError("a missing step must have a button: $why")
                assertEquals(why, ScreenIntent.Setup(row.string), button.intent)
                assertEquals(why, row.text, button.text)
                assertTrue("a step button is enabled: $why", button.enabled)
                assertEquals("the button sits in its own step block", 1, (node(s, "setup.step.${row.step}") as Box).children.count { it is Action })
            }
        }
        assertEquals("the only button outside a block", listOf("setup.recheck"), render().nodes.filterIsInstance<Action>().map { it.id })
    }

    @Test
    fun `the string for each phone action is the agreed one and the handler can turn it back`() {
        val agreed = mapOf(
            OpenAction.REQUEST_MICROPHONE to "open.microphone",
            OpenAction.REQUEST_NOTIFICATIONS to "open.notifications",
            OpenAction.OVERLAY_PAGE to "open.overlay",
            OpenAction.NOTIFICATION_PAGE to "open.notifications",
            OpenAction.ACCESSIBILITY_LIST to "open.accessibility",
            OpenAction.APP_INFO to "open.appinfo",
        )
        assertEquals(OpenAction.entries.toSet(), agreed.keys)
        for ((action, string) in agreed) assertEquals("string for $action", string, SetupActions.stringFor(action))
        assertEquals(agreed.values.toSet(), SetupActions.OPENING)
        assertEquals(listOf("switch.on", "switch.off", "recheck"), listOf(SetupActions.SWITCH_ON, SetupActions.SWITCH_OFF, SetupActions.RECHECK))
        for (sdk in listOf(29, 30, 33, 36)) {
            for (micAsked in listOf(false, true)) {
                for (notificationsAsked in listOf(false, true)) {
                    val st = state(status(sdkInt = sdk), micAsked = micAsked, notificationsAsked = notificationsAsked)
                    for (step in SetupStep.entries) {
                        val wanted = st.actionFor(step) ?: continue
                        val back = SetupActions.openActionFor(SetupActions.stringFor(wanted), st)
                        assertEquals("the string for $step at sdk $sdk, $st does not map back to the action", wanted, back)
                    }
                }
            }
        }
    }

    @Test
    fun `a string that opens nothing, or a notifications tap with nothing left to open, maps to no action`() {
        for (bad in listOf("", "open", "OPEN.OVERLAY", "open.overlay ", " open.overlay", "open.unknown", "switch.on", "switch.off", "recheck")) {
            assertNull("'$bad' must open nothing", SetupActions.openActionFor(bad, state(status())))
        }
        assertNull(SetupActions.openActionFor("open.notifications", state(status(notifications = true))))
        assertEquals(OpenAction.OVERLAY_PAGE, SetupActions.openActionFor("open.overlay", state(allGranted)))
    }

    @Test
    fun `the restricted help shows exactly from version 33 while accessibility is off, as three steps and no button`() {
        val cases = listOf(
            Triple(29, false, false),
            Triple(32, false, false),
            Triple(33, false, true),
            Triple(36, false, true),
            Triple(33, true, false),
            Triple(36, true, false),
        )
        for ((sdk, accessibility, shown) in cases) {
            val s = render(state(status(accessibility = accessibility, sdkInt = sdk)))
            assertEquals("sdk $sdk, accessibility $accessibility", shown, node(s, "setup.step.accessibility.restricted") != null)
        }
        val s = render(state(status(sdkInt = 33)))
        val help = node(s, "setup.step.accessibility.restricted") as Box
        val wording = listOf(SetupTexts.RESTRICTED_INTRO, SetupTexts.RESTRICTED_STEP_1, SetupTexts.RESTRICTED_STEP_2, SetupTexts.RESTRICTED_STEP_3)
        assertEquals(wording, help.children.map { (it as Label).text })
        assertTrue("the help only explains", help.flatten().none { it is Action })
        assertTrue("the help sits inside the accessibility step", (node(s, "setup.step.accessibility") as Box).children.any { it.id == help.id })
    }

    @Test
    fun `the loss note shows only while notifications are off and the limits show always`() {
        val off = render(state(status(notifications = false)))
        assertEquals(SetupTexts.NOTIFICATIONS_LOST, label(off, "setup.step.notifications.lost").text)
        assertNull(node(render(state(status(notifications = true))), "setup.step.notifications.lost"))
        assertTrue((node(off, "setup.step.notifications") as Box).children.any { it.id == "setup.step.notifications.lost" })
        for (st in spread) {
            val s = render(st)
            assertEquals("limits for $st", SetupTexts.ACCESSIBILITY_LIMITS, label(s, "setup.step.accessibility.limits").text)
            assertTrue((node(s, "setup.step.accessibility") as Box).children.any { it.id == "setup.step.accessibility.limits" })
        }
    }

    @Test
    fun `the switch offers off while on and on while off, and only the microphone enables on`() {
        val on = render(state(allGranted, on = true))
        assertEquals(SetupTexts.SWITCH_ON_STATE, label(on, "setup.switch.state").text)
        assertEquals(PaletteSlot.STATE_SENT, label(on, "setup.switch.state").color)
        val off = action(on, "setup.switch.off")
        assertEquals(ScreenIntent.Setup("switch.off"), off.intent)
        assertEquals(SetupTexts.BUTTON_SWITCH_OFF, off.text)
        assertTrue(off.enabled)
        assertNull(node(on, "setup.switch.on"))

        val idle = render(state(allGranted, on = false))
        assertEquals(SetupTexts.SWITCH_OFF_STATE, label(idle, "setup.switch.state").text)
        val turnOn = action(idle, "setup.switch.on")
        assertEquals(ScreenIntent.Setup("switch.on"), turnOn.intent)
        assertEquals(SetupTexts.BUTTON_SWITCH_ON, turnOn.text)
        assertTrue(turnOn.enabled)
        assertNull(node(idle, "setup.switch.off"))
        assertEquals(SetupTexts.SWITCH_HEADING, label(idle, "setup.switch.heading").text)
        assertEquals(SetupTexts.SWITCH_NOTE_ONGOING_NOTICE, label(idle, "setup.switch.note").text)

        val noMic = render(state(status(overlay = true, notifications = true, accessibility = true)))
        assertFalse("the on button needs the microphone", action(noMic, "setup.switch.on").enabled)
        assertEquals(SetupTexts.SWITCH_NEEDS_MICROPHONE, label(noMic, "setup.switch.needsMicrophone").text)
        assertEquals(PaletteSlot.STATE_WARNING, label(noMic, "setup.switch.needsMicrophone").color)
        val micOnly = render(state(status(microphone = true)))
        assertTrue("the microphone alone enables the on button", action(micOnly, "setup.switch.on").enabled)
        assertNull(node(micOnly, "setup.switch.needsMicrophone"))
    }

    @Test
    fun `switching off stays possible when the microphone has been taken away and no line asks for the microphone`() {
        val s = render(state(status(microphone = false), on = true))
        assertTrue("the off button is always enabled", action(s, "setup.switch.off").enabled)
        assertNull("the needs-microphone line belongs to the on button only", node(s, "setup.switch.needsMicrophone"))
        assertNull(node(s, "setup.switch.on"))
    }

    @Test
    fun `the warnings are listed in the model's order with their own texts in the warning colour`() {
        val missing = state(status(microphone = true), on = true)
        assertEquals(
            "precondition: the model lists both",
            listOf(SetupWarning.NO_OVERLAY, SetupWarning.NO_ACCESSIBILITY),
            missing.warnings,
        )
        val texts = mapOf(
            SetupWarning.NO_OVERLAY to Pair("setup.switch.warning.overlay", SetupTexts.WARNING_NO_OVERLAY),
            SetupWarning.NO_ACCESSIBILITY to Pair("setup.switch.warning.accessibility", SetupTexts.WARNING_NO_ACCESSIBILITY),
        )
        assertEquals(SetupWarning.entries.toSet(), texts.keys)
        for (st in spread + missing) {
            val shown = (node(render(st), "setup.switch") as Box).children.filter { it.id.startsWith("setup.switch.warning.") }
            assertEquals("warnings for $st", st.warnings.map { texts.getValue(it).first }, shown.map { it.id })
            for (w in shown.map { it as Label }) {
                assertEquals(PaletteSlot.STATE_WARNING, w.color)
                assertEquals(texts.values.first { it.first == w.id }.second, w.text)
            }
        }
        assertEquals(2, (node(render(missing), "setup.switch") as Box).children.count { it.id.startsWith("setup.switch.warning.") })
    }

    @Test
    fun `check again is always there and the notice shows its own text in the danger colour only when there is one`() {
        for (st in spread) {
            val button = action(render(st), "setup.recheck")
            assertEquals(SetupTexts.BUTTON_CHECK_AGAIN, button.text)
            assertEquals(ScreenIntent.Setup("recheck"), button.intent)
            assertTrue(button.enabled)
        }
        val fixed = listOf(SetupNotice.COULD_NOT_CHECK, SetupNotice.COULD_NOT_OPEN, SetupNotice.SWITCH_FAILED, SetupNotice.NOT_ACCEPTED)
        val wanted = listOf(
            SetupTexts.NOTICE_COULD_NOT_CHECK,
            SetupTexts.NOTICE_COULD_NOT_OPEN,
            SetupTexts.NOTICE_SWITCH_FAILED,
            SetupTexts.NOTICE_NOT_ACCEPTED,
        )
        assertEquals(wanted, fixed.map { it.text })
        for (notice in fixed + SetupNotice.Refused("The app says no.")) {
            val shown = label(render(notice = notice), "setup.notice")
            assertEquals(notice.text, shown.text)
            assertEquals(PaletteSlot.DANGER, shown.color)
        }
        assertEquals("The app says no.", SetupNotice.Refused("The app says no.").text)
        assertNull(node(render(), "setup.notice"))
    }

    @Test
    fun `only the allowed slots are used and the sent and warning colours mean what they say`() {
        val allowed = setOf(
            PaletteSlot.BACKGROUND,
            PaletteSlot.SURFACE,
            PaletteSlot.TEXT,
            PaletteSlot.TEXT_MUTED,
            PaletteSlot.STATE_SENT,
            PaletteSlot.STATE_WARNING,
            PaletteSlot.DANGER,
        )
        val sentTexts = setOf(SetupTexts.STATUS_GRANTED, SetupTexts.SWITCH_ON_STATE)
        val warningTexts = setOf(
            SetupTexts.STATUS_NOT_GRANTED,
            SetupTexts.SWITCH_NEEDS_MICROPHONE,
            SetupTexts.WARNING_NO_OVERLAY,
            SetupTexts.WARNING_NO_ACCESSIBILITY,
        )
        for (st in spread) {
            for (n in nodesOf(render(st, SetupNotice.SWITCH_FAILED))) {
                when (n) {
                    is Label -> {
                        assertTrue("slot ${n.color} on ${n.id}", n.color in allowed)
                        if (n.color == PaletteSlot.STATE_SENT) assertTrue("sent colour on ${n.id}", n.text in sentTexts)
                        if (n.color == PaletteSlot.STATE_WARNING) assertTrue("warning colour on ${n.id}", n.text in warningTexts)
                    }
                    is Box -> assertTrue("slot ${n.background} on box ${n.id}", n.background in allowed)
                    is Action, is TrimStripe -> Unit
                }
            }
        }
    }

    @Test
    fun `every text on the screen is one of the constants in the texts object`() {
        val constants = SetupTexts::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { field ->
                field.isAccessible = true
                field.get(SetupTexts) as String
            }
            .toSet()
        assertTrue("the texts object was read as nearly empty: ${constants.size}", constants.size >= 35)
        for (st in spread) {
            for (n in nodesOf(render(st, SetupNotice.COULD_NOT_CHECK))) {
                val text = when (n) {
                    is Label -> n.text
                    is Action -> n.text
                    is Box, is TrimStripe -> continue
                }
                assertTrue("'$text' on ${n.id} is not a constant of the texts object", text in constants)
            }
        }
    }

    @Test
    fun `every button reports a setup intent with one of the eight agreed strings`() {
        val agreed = setOf(
            "open.overlay", "open.microphone", "open.notifications", "open.accessibility", "open.appinfo",
            "switch.on", "switch.off", "recheck",
        )
        for (st in spread) {
            val actions = nodesOf(render(st)).filterIsInstance<Action>()
            assertTrue("no button at all for $st", actions.isNotEmpty())
            for (a in actions) assertTrue("${a.id} reports ${setupAction(a)}", setupAction(a) in agreed)
        }
    }
}
