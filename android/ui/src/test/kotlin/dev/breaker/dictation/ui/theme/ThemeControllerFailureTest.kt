package dev.breaker.dictation.ui.theme

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.testing.FAILURE_MARKER
import dev.breaker.dictation.ui.testing.FAILURE_URL
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The theme controller when the store fails, and its observers. */
class ThemeControllerFailureTest {
    private fun storeWith(mode: StoredThemeMode) = FakeSettingsStore(NONDEFAULT.copy(themeMode = mode))

    /** What a screen could be handed: the state's text and the outcome's name hold no value from a failure. */
    private fun assertNothingLeaks(state: ThemeState, outcome: ThemeOutcome) {
        val shown = "$state $outcome"
        for (secret in listOf(FAILURE_MARKER, FAILURE_URL, NONDEFAULT.serverUrl, "REF-9f3k", "IllegalState")) {
            assertFalse("$secret in $shown", shown.contains(secret))
        }
    }

    @Test
    fun `a save that throws changes nothing, tells nobody and the next try works`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        var calls = 0
        controller.addObserver { calls++ }
        val before = controller.state
        store.failSave = true
        val outcome = controller.toggle()
        assertEquals(ThemeOutcome.COULD_NOT_SAVE, outcome)
        assertTrue("the save was never reached", store.saveAttempts >= 1)
        assertEquals(before, controller.state)
        assertEquals(NONDEFAULT, store.current)
        assertEquals(emptyList<Any>(), store.saves)
        assertEquals(0, calls)
        assertNothingLeaks(controller.state, outcome)
        store.failSave = false
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(ShownThemeMode.DARK, controller.state.shown)
        assertEquals(1, calls)
    }

    @Test
    fun `a failed follow-the-phone save changes nothing either`() {
        val store = storeWith(StoredThemeMode.DARK)
        val controller = ThemeController(store, systemIsDark = false)
        val before = controller.state
        store.failSave = true
        assertEquals(ThemeOutcome.COULD_NOT_SAVE, controller.useSystem())
        assertTrue("the save was never reached", store.saveAttempts >= 1)
        assertEquals(before, controller.state)
        assertEquals(StoredThemeMode.DARK, store.current.themeMode)
    }

    @Test
    fun `a load that throws on an action saves nothing and the next try keeps every field`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        val before = controller.state
        val loadsBefore = store.loadAttempts
        store.failLoad = true
        val toggled = controller.toggle()
        assertEquals(ThemeOutcome.COULD_NOT_READ, toggled)
        assertEquals(ThemeOutcome.COULD_NOT_READ, controller.useSystem())
        assertTrue("the load was never reached", store.loadAttempts >= loadsBefore + 2)
        assertEquals(0, store.saveAttempts)
        assertEquals(emptyList<Any>(), store.saves)
        assertEquals(before, controller.state)
        assertNothingLeaks(controller.state, toggled)
        store.failLoad = false
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(NONDEFAULT.copy(themeMode = StoredThemeMode.DARK), store.saves.single())
    }

    @Test
    fun `a load that throws while the controller is made shows the phone's mode and writes nothing`() {
        for (phoneDark in listOf(true, false)) {
            val store = storeWith(StoredThemeMode.LIGHT)
            store.failLoad = true
            val controller = ThemeController(store, systemIsDark = phoneDark)
            val phone = if (phoneDark) ShownThemeMode.DARK else ShownThemeMode.LIGHT
            assertEquals(ThemeState(StoredThemeMode.SYSTEM, phone, loaded = false), controller.state)
            assertTrue("the load was never reached", store.loadAttempts >= 1)
            assertEquals(0, store.saveAttempts)
            assertNothingLeaks(controller.state, ThemeOutcome.COULD_NOT_READ)
        }
    }

    @Test
    fun `after a failed first read a later toggle saves once with every field kept and loaded turns true`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        store.failLoad = true
        val controller = ThemeController(store, systemIsDark = false)
        store.failLoad = false
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(listOf(NONDEFAULT.copy(themeMode = StoredThemeMode.DARK)), store.saves)
        assertEquals(ThemeState(StoredThemeMode.DARK, ShownThemeMode.DARK, loaded = true), controller.state)
    }

    @Test
    fun `a failed action never changes loaded`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        store.failLoad = true
        val controller = ThemeController(store, systemIsDark = false)
        store.failLoad = false
        store.failSave = true
        assertEquals(ThemeOutcome.COULD_NOT_SAVE, controller.toggle())
        assertTrue("the save was never reached", store.saveAttempts >= 1)
        assertFalse(controller.state.loaded)
        store.failSave = false
        store.failLoad = true
        assertEquals(ThemeOutcome.COULD_NOT_READ, controller.toggle())
        assertFalse(controller.state.loaded)
    }

    @Test
    fun `observers are told after the save and see the new stored value`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        val storedWhenTold = mutableListOf<StoredThemeMode>()
        val statesGiven = mutableListOf<ThemeState>()
        controller.addObserver { given ->
            store.events.add("notify")
            storedWhenTold.add(store.load().themeMode)
            statesGiven.add(given)
        }
        assertEquals(emptyList<String>(), store.events)
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(listOf("save", "notify"), store.events)
        assertEquals(listOf(StoredThemeMode.DARK), storedWhenTold)
        assertEquals(listOf(ThemeState(StoredThemeMode.DARK, ShownThemeMode.DARK, true)), statesGiven)
    }

    @Test
    fun `an observer that throws does not stop the others and the outcome stays CHANGED`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        var laterCalls = 0
        controller.addObserver { throw IllegalStateException("ZZ-observer $FAILURE_MARKER") }
        controller.addObserver { laterCalls++ }
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(1, laterCalls)
        assertEquals(1, store.saveAttempts)
        assertEquals(ShownThemeMode.DARK, controller.state.shown)
    }

    @Test
    fun `adding gives no call at once and the same observer added twice is called once`() {
        val controller = ThemeController(storeWith(StoredThemeMode.LIGHT), systemIsDark = false)
        var calls = 0
        val observer: (ThemeState) -> Unit = { calls++ }
        controller.addObserver(observer)
        controller.addObserver(observer)
        assertEquals(0, calls)
        controller.toggle()
        assertEquals(1, calls)
    }

    @Test
    fun `a removed observer is not called again and the others still are`() {
        val controller = ThemeController(storeWith(StoredThemeMode.LIGHT), systemIsDark = false)
        var removedCalls = 0
        var keptCalls = 0
        val removed: (ThemeState) -> Unit = { removedCalls++ }
        controller.addObserver(removed)
        controller.addObserver { keptCalls++ }
        controller.toggle()
        controller.removeObserver(removed)
        controller.toggle()
        assertEquals(1, removedCalls)
        assertEquals(2, keptCalls)
    }

    @Test
    fun `an observer that removes itself while it is called does not crash or skip the next one`() {
        val controller = ThemeController(storeWith(StoredThemeMode.LIGHT), systemIsDark = false)
        var selfCalls = 0
        var nextCalls = 0
        lateinit var self: (ThemeState) -> Unit
        self = {
            selfCalls++
            controller.removeObserver(self)
        }
        controller.addObserver(self)
        controller.addObserver { nextCalls++ }
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(1, selfCalls)
        assertEquals(2, nextCalls)
    }

    @Test
    fun `removing an observer that was never added does nothing`() {
        val controller = ThemeController(storeWith(StoredThemeMode.LIGHT), systemIsDark = false)
        controller.removeObserver { }
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
    }
}
