package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.ui.BreakerSwitch
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import dev.breaker.dictation.ui.testing.SETUP_FAILURE_MARKER
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The set-up handler: what each tap asks of the phone and of the switch, what each failure
 * says, what is remembered between drawings, and what the screen is rebuilt from.
 *
 * The fakes count every call, so a test can say "once", "not at all" and "again".
 */
class SetupIntentHandlerTest {
    private val nothing = PlatformStatus(
        overlay = false,
        microphone = false,
        notifications = false,
        accessibility = false,
        sdkInt = 36,
    )

    /** The phone with the one thing that gates the switch: the microphone. */
    private val withMicrophone = nothing.copy(microphone = true)

    private class Fixture(phone: PlatformStatus, on: Boolean = false) {
        val platform = FakeSetupPlatform(phone)
        val switch = FakeBreakerSwitch(on)
        val handler = SetupIntentHandler(platform, switch, SetupScreen())
    }

    private fun tap(action: String) = ScreenIntent.Setup(action)

    private fun Screen.node(id: String): Node? = nodes.flatMap { it.flatten() }.firstOrNull { it.id == id }

    /** The text of the label or button with this id, or null. */
    private fun Screen.text(id: String): String? = when (val n = node(id)) {
        is Label -> n.text
        is Action -> n.text
        else -> null
    }

    /** The action string the button with this id reports, or null when there is no such button. */
    private fun Screen.reports(id: String): String? = ((node(id) as? Action)?.intent as? ScreenIntent.Setup)?.action

    private fun Screen.notice(): String? = text("setup.notice")

    private fun Screen.withoutNotice(): Screen = copy(nodes = nodes.filterNot { it.id == "setup.notice" })

    private fun assertNoNotice(screen: Screen) = assertNull("no notice expected", screen.notice())

    @Test
    fun `current reads the phone and the switch afresh on every call and replaces a stale state`() {
        val f = Fixture(nothing)
        val first = f.handler.current()
        assertEquals(1, f.platform.statusCalls)
        assertEquals(1, f.switch.isOnCalls)
        assertEquals(first, f.handler.current())
        assertEquals(2, f.platform.statusCalls)
        assertEquals(2, f.switch.isOnCalls)

        f.platform.answer = nothing.copy(microphone = true)
        f.switch.on = true
        val third = f.handler.current()
        assertNotEquals(first, third)
        assertEquals(SetupTexts.STATUS_GRANTED, third.text("setup.step.microphone.status"))
        assertEquals(SetupTexts.BUTTON_SWITCH_OFF, third.text("setup.switch.off"))
        assertNull(third.node("setup.step.microphone.button"))
        assertNoNotice(third)
    }

    private class OpenRow(val phone: PlatformStatus, val string: String, val expected: OpenAction)

    @Test
    fun `an open tap asks the phone once for the mapped action and touches nothing else`() {
        val rows = listOf(
            OpenRow(nothing, "open.overlay", OpenAction.OVERLAY_PAGE),
            OpenRow(nothing, "open.microphone", OpenAction.REQUEST_MICROPHONE),
            OpenRow(nothing, "open.appinfo", OpenAction.APP_INFO),
            OpenRow(nothing, "open.accessibility", OpenAction.ACCESSIBILITY_LIST),
            OpenRow(nothing.copy(sdkInt = 36), "open.notifications", OpenAction.REQUEST_NOTIFICATIONS),
            OpenRow(nothing.copy(sdkInt = 29), "open.notifications", OpenAction.NOTIFICATION_PAGE),
        )
        for (row in rows) {
            val f = Fixture(row.phone)
            f.handler.current()
            val after = f.handler.handle(tap(row.string))
            assertEquals(row.string, listOf(row.expected), f.platform.opened)
            assertNoNotice(after)
            assertEquals(row.string, 0, f.switch.switchOnCalls + f.switch.switchOffCalls)
        }
    }

    @Test
    fun `a notifications tap does nothing once notifications are granted meanwhile`() {
        val f = Fixture(nothing)
        f.handler.current()
        f.platform.answer = nothing.copy(notifications = true)
        f.handler.current()
        val after = f.handler.handle(tap("open.notifications"))
        assertEquals(emptyList<OpenAction>(), f.platform.opened)
        assertNoNotice(after)
    }

    @Test
    fun `a microphone prompt that opened is remembered so the next button opens the app info`() {
        val f = Fixture(nothing)
        assertEquals("open.microphone", f.handler.current().reports("setup.step.microphone.button"))
        val after = f.handler.handle(tap("open.microphone"))
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE), f.platform.opened)
        assertEquals("open.appinfo", after.reports("setup.step.microphone.button"))
        assertEquals(SetupTexts.BUTTON_APP_INFO, after.text("setup.step.microphone.button"))

        val again = f.handler.handle(tap("open.appinfo"))
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE, OpenAction.APP_INFO), f.platform.opened)
        assertEquals("a page that opened is not a prompt", "open.appinfo", again.reports("setup.step.microphone.button"))
        assertEquals("the memory is the handler's own", "open.microphone", Fixture(nothing).handler.current().reports("setup.step.microphone.button"))
    }

    @Test
    fun `a notifications prompt that opened is remembered but a settings page is not a prompt`() {
        val modern = Fixture(nothing.copy(sdkInt = 36))
        modern.handler.current()
        modern.handler.handle(tap("open.notifications"))
        modern.handler.handle(tap("open.notifications"))
        assertEquals(listOf(OpenAction.REQUEST_NOTIFICATIONS, OpenAction.NOTIFICATION_PAGE), modern.platform.opened)
        assertEquals(SetupTexts.BUTTON_NOTIFICATION_PAGE, modern.handler.current().text("setup.step.notifications.button"))

        val old = Fixture(nothing.copy(sdkInt = 29))
        old.handler.current()
        old.handler.handle(tap("open.notifications"))
        old.handler.handle(tap("open.notifications"))
        assertEquals(listOf(OpenAction.NOTIFICATION_PAGE, OpenAction.NOTIFICATION_PAGE), old.platform.opened)
    }

    @Test
    fun `a phone that cannot open says so, changes nothing else and forgets nothing it was not asked`() {
        for (breakHow in listOf("not possible", "throws")) {
            val f = Fixture(nothing)
            val before = f.handler.current()
            if (breakHow == "throws") f.platform.failOpen = true else f.platform.openResult = OpenResult.NOT_POSSIBLE
            val after = f.handler.handle(tap("open.microphone"))
            assertEquals(breakHow, SetupTexts.NOTICE_COULD_NOT_OPEN, after.notice())
            assertEquals(breakHow, before, after.withoutNotice())
            assertEquals(breakHow, "open.microphone", after.reports("setup.step.microphone.button"))
            assertEquals(breakHow, listOf(OpenAction.REQUEST_MICROPHONE), f.platform.opened)
        }
    }

    @Test
    fun `switching on that works leaves no notice and the screen follows the switch`() {
        for (result in listOf(BreakerSwitch.Result.On, BreakerSwitch.Result.AlreadyOn)) {
            val f = Fixture(nothing.copy(microphone = true))
            f.switch.nextResult = result
            f.handler.current()
            val after = f.handler.handle(tap("switch.on"))
            assertEquals(1, f.switch.switchOnCalls)
            assertEquals(0, f.switch.switchOffCalls)
            assertNoNotice(after)
            assertEquals(SetupTexts.SWITCH_ON_STATE, after.text("setup.switch.state"))
            assertEquals("switch.off", after.reports("setup.switch.off"))
            assertEquals(emptyList<OpenAction>(), f.platform.opened)
        }
    }

    @Test
    fun `a refusal shows exactly the app's sentence and the switch stays off`() {
        val f = Fixture(withMicrophone)
        f.switch.nextResult = BreakerSwitch.Result.Refused("Android did not let Breaker start just now.")
        f.handler.current()
        val after = f.handler.handle(tap("switch.on"))
        assertEquals("Android did not let Breaker start just now.", after.notice())
        assertEquals(1, f.switch.switchOnCalls)
        assertEquals(SetupTexts.SWITCH_OFF_STATE, after.text("setup.switch.state"))
        assertNotNull(after.node("setup.switch.on"))
    }

    @Test
    fun `a refusal with no words, or a switch that throws, says the fixed failure sentence`() {
        for (blank in listOf("", "   ")) {
            val f = Fixture(withMicrophone)
            f.switch.nextResult = BreakerSwitch.Result.Refused(blank)
            assertEquals("'$blank'", SetupTexts.NOTICE_SWITCH_FAILED, f.handler.handle(tap("switch.on")).notice())
        }
        val f = Fixture(withMicrophone)
        f.switch.failSwitchOn = true
        val after = f.handler.handle(tap("switch.on"))
        assertEquals(SetupTexts.NOTICE_SWITCH_FAILED, after.notice())
        assertEquals(1, f.switch.switchOnCalls)
        assertEquals(SetupTexts.SWITCH_OFF_STATE, after.text("setup.switch.state"))
    }

    @Test
    fun `a switch on tap reaches the app even with the microphone missing, because the app decides`() {
        val f = Fixture(nothing)
        val before = f.handler.current()
        assertFalse("the screen disables the button", (before.node("setup.switch.on") as Action).enabled)
        f.switch.nextResult = BreakerSwitch.Result.Refused("The app says no.")
        assertEquals("The app says no.", f.handler.handle(tap("switch.on")).notice())
        assertEquals("the handler did not refuse it itself", 1, f.switch.switchOnCalls)

        f.switch.nextResult = BreakerSwitch.Result.On
        val after = f.handler.handle(tap("switch.on"))
        assertEquals(2, f.switch.switchOnCalls)
        assertNoNotice(after)
        assertEquals(SetupTexts.SWITCH_ON_STATE, after.text("setup.switch.state"))
    }

    @Test
    fun `switching off calls the switch each time, is fine when already off, and a throw says it failed`() {
        val f = Fixture(nothing.copy(microphone = true), on = true)
        assertEquals("switch.off", f.handler.current().reports("setup.switch.off"))
        val first = f.handler.handle(tap("switch.off"))
        assertEquals(1, f.switch.switchOffCalls)
        assertNoNotice(first)
        assertEquals(SetupTexts.SWITCH_OFF_STATE, first.text("setup.switch.state"))
        val second = f.handler.handle(tap("switch.off"))
        assertEquals(2, f.switch.switchOffCalls)
        assertNoNotice(second)
        assertEquals(0, f.switch.switchOnCalls)

        val broken = Fixture(nothing, on = true)
        broken.switch.failSwitchOff = true
        val after = broken.handler.handle(tap("switch.off"))
        assertEquals(SetupTexts.NOTICE_SWITCH_FAILED, after.notice())
        assertEquals(1, broken.switch.switchOffCalls)
        assertEquals("the switch is still on", SetupTexts.SWITCH_ON_STATE, after.text("setup.switch.state"))
    }

    @Test
    fun `check again draws the screen afresh and asks the phone to open nothing and the switch to change nothing`() {
        val f = Fixture(nothing)
        f.handler.current()
        f.platform.answer = nothing.copy(overlay = true)
        val statusBefore = f.platform.statusCalls
        val switchBefore = f.switch.isOnCalls
        val after = f.handler.handle(tap("recheck"))
        assertEquals(statusBefore + 1, f.platform.statusCalls)
        assertEquals(switchBefore + 1, f.switch.isOnCalls)
        assertEquals(SetupTexts.STATUS_GRANTED, after.text("setup.step.overlay.status"))
        assertEquals(emptyList<OpenAction>(), f.platform.opened)
        assertEquals(0, f.switch.switchOnCalls + f.switch.switchOffCalls)
        assertNoNotice(after)
    }

    @Test
    fun `a phone that cannot be read keeps the last good answers and says so, and a first read shows nothing granted and off`() {
        val f = Fixture(nothing.copy(microphone = true, sdkInt = 33), on = true)
        val good = f.handler.current()
        assertNoNotice(good)
        f.platform.failStatus = true
        f.platform.answer = nothing
        val broken = f.handler.current()
        assertEquals(SetupTexts.NOTICE_COULD_NOT_CHECK, broken.notice())
        assertEquals(good, broken.withoutNotice())
        f.platform.failStatus = false
        assertNoNotice(f.handler.current())

        val first = Fixture(nothing.copy(microphone = true, overlay = true))
        first.platform.failStatus = true
        val blank = first.handler.current()
        assertEquals(SetupTexts.NOTICE_COULD_NOT_CHECK, blank.notice())
        for (step in listOf("overlay", "microphone", "notifications", "accessibility")) {
            assertEquals(step, SetupTexts.STATUS_NOT_GRANTED, blank.text("setup.step.$step.status"))
        }
        assertEquals(SetupTexts.SWITCH_OFF_STATE, blank.text("setup.switch.state"))
    }

    @Test
    fun `a switch that cannot be read keeps the last known value and says so`() {
        val f = Fixture(nothing, on = true)
        assertEquals(SetupTexts.SWITCH_ON_STATE, f.handler.current().text("setup.switch.state"))
        f.switch.failIsOn = true
        f.switch.on = false
        val broken = f.handler.current()
        assertEquals(SetupTexts.NOTICE_COULD_NOT_CHECK, broken.notice())
        assertEquals(SetupTexts.SWITCH_ON_STATE, broken.text("setup.switch.state"))
        f.switch.failIsOn = false
        val healed = f.handler.current()
        assertNoNotice(healed)
        assertEquals(SetupTexts.SWITCH_OFF_STATE, healed.text("setup.switch.state"))

        val never = Fixture(nothing, on = true)
        never.switch.failIsOn = true
        assertEquals(SetupTexts.SWITCH_OFF_STATE, never.handler.current().text("setup.switch.state"))
    }

    @Test
    fun `an action that failed is the notice shown even when the read failed too, and the read failure follows`() {
        val f = Fixture(nothing)
        f.handler.current()
        f.platform.failStatus = true
        f.platform.openResult = OpenResult.NOT_POSSIBLE
        assertEquals(SetupTexts.NOTICE_COULD_NOT_OPEN, f.handler.handle(tap("open.overlay")).notice())
        assertEquals(SetupTexts.NOTICE_COULD_NOT_CHECK, f.handler.current().notice())
    }

    @Test
    fun `an unknown action or another kind of intent is not accepted and nothing is asked of anyone`() {
        val intents = listOf(
            tap(""), tap("open"), tap("open.overlay "), tap("OPEN.OVERLAY"), tap("open.unknown"),
            tap("switch"), tap("switch.toggle"), tap("recheck!"),
            ScreenIntent.ToggleTheme, ScreenIntent.UseSystemTheme, ScreenIntent.SetRoutingMode("LOCAL"), ScreenIntent.SetSetting("a", "b"),
        )
        for (intent in intents) {
            val f = Fixture(nothing.copy(microphone = true), on = true)
            val before = f.handler.current()
            val statusBefore = f.platform.statusCalls
            val switchBefore = f.switch.isOnCalls
            val after = f.handler.handle(intent)
            assertEquals("$intent", SetupTexts.NOTICE_NOT_ACCEPTED, after.notice())
            assertEquals("$intent", before, after.withoutNotice())
            assertEquals("$intent", statusBefore, f.platform.statusCalls)
            assertEquals("$intent", switchBefore, f.switch.isOnCalls)
            assertEquals("$intent", emptyList<OpenAction>(), f.platform.opened)
            assertEquals("$intent", 0, f.switch.switchOnCalls + f.switch.switchOffCalls)
        }
    }

    @Test
    fun `a notice lasts exactly one drawing`() {
        val f = Fixture(withMicrophone)
        f.handler.current()
        f.platform.openResult = OpenResult.NOT_POSSIBLE
        assertNotNull(f.handler.handle(tap("open.overlay")).notice())
        assertNoNotice(f.handler.current())

        f.handler.handle(tap("nonsense"))
        assertNoNotice(f.handler.handle(tap("recheck")))

        f.switch.nextResult = BreakerSwitch.Result.Refused("Not now.")
        assertEquals("Not now.", f.handler.handle(tap("switch.on")).notice())
        assertNoNotice(f.handler.current())
    }

    @Test
    fun `a tap before the first drawing still works and reads the phone once`() {
        val f = Fixture(nothing)
        val after = f.handler.handle(tap("open.overlay"))
        assertEquals(listOf(OpenAction.OVERLAY_PAGE), f.platform.opened)
        assertEquals(1, f.platform.statusCalls)
        assertNoNotice(after)
    }

    @Test
    fun `no exception text ever reaches the screen`() {
        val f = Fixture(nothing, on = true)
        f.platform.failStatus = true
        f.platform.failOpen = true
        f.switch.failIsOn = true
        f.switch.failSwitchOn = true
        f.switch.failSwitchOff = true
        val screens = listOf(
            f.handler.current(),
            f.handler.handle(tap("open.microphone")),
            f.handler.handle(tap("switch.on")),
            f.handler.handle(tap("switch.off")),
            f.handler.handle(tap("recheck")),
        )
        for (screen in screens) {
            val texts = screen.nodes.flatMap { it.flatten() }.mapNotNull { screen.text(it.id) }
            assertTrue("texts were read", texts.isNotEmpty())
            assertTrue("an exception message reached the screen", texts.none { SETUP_FAILURE_MARKER in it || "failed:" in it })
        }
    }

    @Test
    fun `the handler names every intent so none can be added without a branch`() {
        val source = handlerSource()
        val code = withoutComments(source)
        for (name in listOf("ToggleTheme", "UseSystemTheme", "SetRoutingMode", "SetSetting", "Setup")) {
            assertTrue("no branch for ScreenIntent.$name", Regex("""ScreenIntent\.$name\b""").containsMatchIn(code))
        }
        val branches = code.lines().count { it.trim().startsWith("ScreenIntent.") || "is ScreenIntent." in it }
        assertTrue("the branch on every intent was not found: $branches", branches >= 5)
        assertFalse("a catch-all branch would swallow an intent added later", hasCatchAllBranch(source))
        assertTrue("the control must fire on a branch that catches everything", hasCatchAllBranch(CONTROL_SOURCE))
    }

    /** Whether a `when` has a branch that takes everything left over. */
    private fun hasCatchAllBranch(text: String): Boolean =
        withoutComments(text).lines().any { it.trimStart().startsWith("else ->") }

    /** Comments do not branch, so a line of prose about one must not be read as a branch. */
    private fun withoutComments(text: String): String =
        text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .joinToString("\n")

    /** The handler's own source, so a branch the code does not need cannot be added unnoticed. */
    private fun handlerSource(): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/screen/onboarding/SetupIntentHandler.kt")
        assertTrue("the handler source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** A `when` that takes everything left over, which the scan above must notice. */
    private val CONTROL_SOURCE = """
        fun f(intent: ScreenIntent): Int = when (intent) {
            ScreenIntent.ToggleTheme -> 1
            else -> 0
        }
    """.trimIndent()
}
