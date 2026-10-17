package dev.breaker.dictation.ui.screen.onboarding

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone prompt's memory through the settings store. The store is written only after the
 * system dialog came back and the microphone is still not granted; a store that cannot be read or
 * saved is tolerated. Each test draws the screen first, as the host does on attachment, and taps
 * the microphone button the way the screen reports it.
 */
class SetupMicStepFlagTest {
    /** The phone with the microphone denied, the rationale off and the version the screen expects. */
    private val denied = PlatformStatus(
        overlay = false,
        microphone = false,
        notifications = false,
        accessibility = false,
        sdkInt = 36,
        microphoneRationale = false,
    )

    /** The handler built the way the app builds it, with the store as the last argument; null builds it with no store. */
    private class Rig(phone: PlatformStatus, store: FakeSettingsStore?) {
        val platform = FakeSetupPlatform(phone)
        val handler = SetupIntentHandler(
            platform,
            FakeBreakerSwitch(false),
            { false },
            { null },
            { 0 },
            { },
            SetupScreen(),
            store,
        )
    }

    private val micButton = "setup.step.microphone.button"

    private fun tapMicrophone() = ScreenIntent.Setup(SetupActions.OPEN_MICROPHONE)

    private fun Screen.node(id: String): Node? = nodes.flatMap { it.flatten() }.firstOrNull { it.id == id }

    private fun Screen.text(id: String): String? = when (val n = node(id)) {
        is Label -> n.text
        is Action -> n.text
        else -> null
    }

    private fun Screen.reports(id: String): String? = ((node(id) as? Action)?.intent as? ScreenIntent.Setup)?.action

    private fun Screen.notice(): String? = text(SETUP_NOTICE_ID)

    @Test
    fun `opening the screen writes nothing`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val rig = Rig(denied, store)
        rig.handler.current()
        rig.handler.current()
        assertEquals(0, store.saveAttempts)
        assertTrue(store.events.isEmpty())
        assertFalse(store.current.microphoneAsked)
    }

    @Test
    fun `the tap alone writes nothing to the store and the next draw after the dialog returned writes the flag`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val rig = Rig(denied, store)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE), rig.platform.opened)
        assertEquals("the tap saved", 0, store.saveAttempts)
        rig.handler.current()
        assertTrue(store.current.microphoneAsked)
        assertEquals(1, store.saves.size)
    }

    @Test
    fun `a dialog that ended in a grant leaves the flag unwritten`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val rig = Rig(denied, store)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        rig.platform.answer = denied.copy(microphone = true)
        rig.handler.current()
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE), rig.platform.opened)
        assertEquals(0, store.saveAttempts)
        assertFalse(store.current.microphoneAsked)
    }

    @Test
    fun `a new handler over a store that says asked and no rationale opens the settings page on the first tap`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = true))
        val rig = Rig(denied, store)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.APP_INFO), rig.platform.opened)
        assertEquals(0, store.saveAttempts)
    }

    @Test
    fun `a store that says asked with rationale true asks again`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = true))
        val rig = Rig(denied.copy(microphoneRationale = true), store)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE), rig.platform.opened)
    }

    @Test
    fun `a store that says asked is read even when the screen was drawn before`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = true))
        val rig = Rig(denied.copy(microphoneRationale = true), store)
        val first = rig.handler.current()
        assertEquals("open.microphone", first.reports(micButton))
        rig.platform.answer = denied.copy(microphoneRationale = false)
        val second = rig.handler.current()
        assertEquals("open.appinfo", second.reports(micButton))
        rig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.APP_INFO), rig.platform.opened)
        assertEquals(0, store.saveAttempts)
    }

    @Test
    fun `a request the system refused is never remembered`() {
        val refused = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val refusedRig = Rig(denied, refused)
        refusedRig.platform.openResult = OpenResult.NOT_POSSIBLE
        refusedRig.handler.current()
        refusedRig.handler.handle(tapMicrophone())
        refusedRig.handler.current()
        assertEquals(0, refused.saveAttempts)
        refusedRig.handler.handle(tapMicrophone())
        assertEquals(
            listOf(OpenAction.REQUEST_MICROPHONE, OpenAction.REQUEST_MICROPHONE),
            refusedRig.platform.opened,
        )

        val thrown = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val thrownRig = Rig(denied, thrown)
        thrownRig.platform.failOpen = true
        thrownRig.handler.current()
        thrownRig.handler.handle(tapMicrophone())
        thrownRig.handler.current()
        assertEquals(0, thrown.saveAttempts)
        thrownRig.handler.handle(tapMicrophone())
        assertEquals(
            listOf(OpenAction.REQUEST_MICROPHONE, OpenAction.REQUEST_MICROPHONE),
            thrownRig.platform.opened,
        )
    }

    @Test
    fun `a tap and a second tap with no draw between opens the settings page and writes the flag`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val rig = Rig(denied, store)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        rig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE, OpenAction.APP_INFO), rig.platform.opened)
        assertEquals(1, store.saveAttempts)
        assertTrue(store.current.microphoneAsked)
    }

    @Test
    fun `a store that cannot be read is tolerated`() {
        val broken = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        broken.failLoad = true
        val brokenRig = Rig(denied, broken)
        val working = Rig(denied, FakeSettingsStore(AppSettings().copy(microphoneAsked = false)))
        val drawn = brokenRig.handler.current()
        val expected = working.handler.current()
        assertTrue("the failing read was reached", broken.loadAttempts >= 1)
        assertEquals(expected.notice(), drawn.notice())
        assertNull(drawn.notice())
        brokenRig.handler.handle(tapMicrophone())
        val second = brokenRig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE, OpenAction.APP_INFO), brokenRig.platform.opened)
        assertNull(second.notice())
    }

    @Test
    fun `a store that cannot be saved is tolerated`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        store.failSave = true
        val rig = Rig(denied, store)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        val drawn = rig.handler.current()
        assertNull(drawn.notice())
        assertEquals(1, store.saveAttempts)
        assertTrue(store.saves.isEmpty())
        val after = rig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE, OpenAction.APP_INFO), rig.platform.opened)
        assertNull(after.notice())
    }

    @Test
    fun `the write keeps the other settings`() {
        val store = FakeSettingsStore(NONDEFAULT.copy(microphoneAsked = false))
        val rig = Rig(denied, store)
        assertFalse("the seed starts unasked", store.current.microphoneAsked)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        rig.handler.current()
        assertEquals(NONDEFAULT.copy(microphoneAsked = true), store.current)
        assertEquals(1, store.saves.size)
    }

    @Test
    fun `without a store the flag is kept in memory`() {
        val rig = Rig(denied, null)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE, OpenAction.APP_INFO), rig.platform.opened)
    }

    @Test
    fun `the redraw right after the tap already offers the settings page and writes nothing to the store`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val rig = Rig(denied, store)
        rig.handler.current()
        val after = rig.handler.handle(tapMicrophone())
        assertEquals(listOf(OpenAction.REQUEST_MICROPHONE), rig.platform.opened)
        assertEquals("open.appinfo", after.reports(micButton))
        assertEquals(SetupTexts.BUTTON_APP_INFO, after.text(micButton))
        assertEquals(0, store.saveAttempts)
    }

    @Test
    fun `a status read that fails keeps the ask pending`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val rig = Rig(denied, store)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        rig.platform.failStatus = true
        rig.handler.current()
        assertEquals(0, store.saveAttempts)
        rig.platform.failStatus = false
        rig.handler.current()
        assertEquals(1, store.saves.size)
        assertTrue(store.current.microphoneAsked)
    }

    @Test
    fun `a written ask is not asked of the phone again`() {
        val store = FakeSettingsStore(AppSettings().copy(microphoneAsked = false))
        val rig = Rig(denied, store)
        rig.handler.current()
        rig.handler.handle(tapMicrophone())
        rig.handler.current()
        val before = rig.platform.statusCalls
        rig.handler.current()
        assertEquals("one read for the draw and none for the pending ask", before + 1, rig.platform.statusCalls)
        assertEquals(1, store.saves.size)
    }
}
