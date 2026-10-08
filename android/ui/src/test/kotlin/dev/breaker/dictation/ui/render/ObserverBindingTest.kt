package dev.breaker.dictation.ui.render

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.ThemeOutcome
import dev.breaker.dictation.ui.theme.ThemeState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binding between the theme choice and the view: when it is listening, when
 * it has stopped listening, and what it must never do to the other listeners.
 *
 * Every test drives a real controller over a store holding settings that differ
 * from the defaults, and every test checks the save really happened before it
 * checks what was told. An observer that hears nothing because nothing was saved
 * is a test that passes for the wrong reason.
 */
class ObserverBindingTest {
    /** The public declaration this module offers an app, written on one line. */
    private val ENTRY_SIGNATURE =
        "fun createSettingsView(context: android.content.Context, " +
            "settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View"

    /** The second public declaration of the entry file, written on one line. */
    private val ONBOARDING_SIGNATURE =
        "fun createOnboardingView(context: android.content.Context, " +
            "switch: BreakerSwitch, accessibilityServiceComponent: String): android.view.View"

    /** The public declaration of the history view, written on one line. */
    private val HISTORY_SIGNATURE =
        "fun createHistoryView(context: android.content.Context, " +
            "history: dev.breaker.dictation.core.port.HistoryStore): android.view.View"

    /** A controller over a store holding a light scheme, with the phone in light mode. */
    private class Fixture {
        val store = FakeSettingsStore(NONDEFAULT.copy(themeMode = StoredThemeMode.LIGHT))
        val themes = ThemeController(store, systemIsDark = false)

        /** What the bound observer was told, in the order it was told it. */
        val told = mutableListOf<ThemeState>()
        val binding = ObserverBinding(themes) { told.add(it) }
    }

    /**
     * Checks that the toggle reached the store as dark.
     *
     * This is the precondition every other assertion in a test rests on: without
     * it a listener that was never called and a listener that was correctly told
     * nothing look the same.
     */
    private fun Fixture.assertToggleSaved() {
        assertEquals("the toggle must reach the store", 1, store.saveAttempts)
        assertEquals(
            "the toggle must be stored as dark",
            listOf(StoredThemeMode.DARK),
            store.saves.map { it.themeMode },
        )
        assertEquals("the stored choice must be the one the listener is told", StoredThemeMode.DARK, store.current.themeMode)
    }

    @Test
    fun `an attached listener is told of a change and a detached one hears nothing`() {
        val fixture = Fixture()
        fixture.binding.attach()
        fixture.themes.toggle()
        fixture.assertToggleSaved()
        assertEquals("an attached listener must be told once", 1, fixture.told.size)
        assertEquals("it must be told the stored choice", StoredThemeMode.DARK, fixture.told.single().stored)

        fixture.binding.detach()
        fixture.themes.useSystem()
        assertEquals("the action after a detach must still be saved", 2, fixture.store.saveAttempts)
        assertEquals("a detached listener must hear nothing", 1, fixture.told.size)
    }

    @Test
    fun `attaching twice listens once and one detach stops every registration`() {
        val fixture = Fixture()
        fixture.binding.attach()
        fixture.binding.attach()
        fixture.themes.toggle()
        fixture.assertToggleSaved()
        assertEquals("a second attach must not add a second listener", 1, fixture.told.size)

        // One detach has to be enough. A binding that built its observer afresh
        // on each attach would have registered two different objects, and the
        // controller matches by identity, so this is where that shows up.
        fixture.binding.detach()
        fixture.themes.useSystem()
        assertEquals(2, fixture.store.saveAttempts)
        assertEquals("one detach must remove every registration", 1, fixture.told.size)
    }

    @Test
    fun `a listener that throws neither stops the others nor undoes the saved change`() {
        val fixture = Fixture()
        val order = mutableListOf<String>()
        fixture.themes.addObserver {
            order.add("failing")
            throw IllegalStateException("a listener that fails")
        }
        fixture.binding.attach()
        fixture.themes.addObserver { order.add("after") }

        val outcome = fixture.themes.toggle()
        fixture.assertToggleSaved()
        assertEquals("the saved change must stand", ThemeOutcome.CHANGED, outcome)
        assertEquals("the failing listener must not stop the ones after it", listOf("failing", "after"), order)
        assertEquals("the bound listener must have been told once", 1, fixture.told.size)
        assertEquals("and told the stored choice", StoredThemeMode.DARK, fixture.told.single().stored)
    }

    @Test
    fun `the bound listener is called in the order the controller holds it`() {
        val fixture = Fixture()
        val order = mutableListOf<String>()
        fixture.themes.addObserver { order.add("first") }
        fixture.binding.attach()
        fixture.themes.addObserver { order.add("third") }
        fixture.themes.toggle()
        fixture.assertToggleSaved()
        assertEquals("the listener added first must be called first", listOf("first", "third"), order)
        assertEquals("the bound listener must have run between the two", 1, fixture.told.size)
    }

    @Test
    fun `a detach before any attach stops nothing and a later attach still listens`() {
        val fixture = Fixture()
        fixture.binding.detach()
        fixture.themes.toggle()
        fixture.assertToggleSaved()
        assertEquals("a listener that was never attached cannot be silenced", 0, fixture.told.size)

        fixture.binding.attach()
        fixture.themes.useSystem()
        assertEquals("the action after the attach must be saved", 2, fixture.store.saveAttempts)
        assertEquals("an attach after a stray detach must listen", 1, fixture.told.size)
        assertEquals("and hear the choice it was not there for", StoredThemeMode.SYSTEM, fixture.told.single().stored)
    }

    @Test
    fun `the binding names no android type and the scan that says so fires on text that does`() {
        val offending = sourceOf("render/ObserverBinding.kt").lines().filter { ANDROID_TYPE.containsMatchIn(it) }
        assertEquals("the binding must name no android type, but found: $offending", emptyList<String>(), offending)
        assertTrue(
            "the scan must fire on an android reference it is meant to catch",
            CONTROL_WITH_ANDROID.lines().any { ANDROID_TYPE.containsMatchIn(it) },
        )
    }

    @Test
    fun `the entry file offers three declarations of settings view, setup view and history view`() {
        val offered = offeredDeclarations(sourceOf("SettingsEntry.kt"))
        assertEquals("exactly three declarations may be offered by the entry file", 3, offered.size)
        assertTrue("the first offered declaration must be the settings view", offered.any { it.startsWith("fun createSettingsView(") })
        assertTrue("the second offered declaration must be the setup view", offered.any { it.startsWith("fun createOnboardingView(") })
        assertTrue("the third offered declaration must be the history view", offered.any { it.startsWith("fun createHistoryView(") })

        val control = offeredDeclarations(CONTROL_WITH_THREE_OFFERED)
        assertEquals("the scan must catch a third offered declaration", 3, control.size)
    }

    @Test
    fun `the settings view takes a context and a settings store and gives back a view`() {
        val flattened = sourceOf("SettingsEntry.kt").replace(ANY_WHITESPACE, " ")
        assertTrue(
            "the pinned declaration must be there with both parameter types",
            flattened.contains(ENTRY_SIGNATURE),
        )
        assertFalse(
            "the scan must not accept a parameter type that has been weakened",
            CONTROL_WEAK_PARAMETER.contains(ENTRY_SIGNATURE),
        )
    }

    @Test
    fun `the setup view takes a context, a switch and a component name and gives back a view`() {
        val flattened = sourceOf("SettingsEntry.kt").replace(ANY_WHITESPACE, " ")
        assertTrue(
            "the pinned declaration must be there with all three parameter types",
            flattened.contains(ONBOARDING_SIGNATURE),
        )
        assertFalse(
            "the scan must not accept a switch parameter that has been weakened",
            CONTROL_WEAK_SWITCH.contains(ONBOARDING_SIGNATURE),
        )
    }

    @Test
    fun `the history view takes a context and a history store and gives back a view`() {
        val flattened = sourceOf("SettingsEntry.kt").replace(ANY_WHITESPACE, " ")
        assertTrue(
            "the pinned declaration must be there with both parameter types",
            flattened.contains(HISTORY_SIGNATURE),
        )
        assertFalse(
            "the scan must not accept a history parameter that has been weakened",
            CONTROL_WEAK_HISTORY.contains(HISTORY_SIGNATURE),
        )
    }

    /** Any android or androidx type name, imported or written out. */
    private val ANDROID_TYPE = Regex("\\bandroidx?\\.[A-Za-z]")

    private val ANY_WHITESPACE = Regex("\\s+")

    /** The words that open a top level declaration, followed by the declaration itself. */
    private val DECLARATION_START =
        Regex("^(fun|val|var|class|object|interface|typealias|annotation class|enum class|sealed class|data class) ")

    /**
     * The top level declarations in [text] that are neither `internal` nor `private`.
     *
     * Comments go first: a line of prose that begins with one of these words is
     * not a declaration, and one that must not be counted must not be able to
     * hide one that should be.
     */
    private fun offeredDeclarations(text: String): List<String> =
        withoutComments(text).lines()
            // A declaration at the top level starts in the first column. A line
            // inside a function body is indented, and trimming first would count
            // every local val in the file as something offered to an app.
            .filter { DECLARATION_START.containsMatchIn(it) }
            .map { it.removeSuffix("{").trimEnd() }
            .filterNot { it.startsWith("internal ") || it.startsWith("private ") }

    /** Comments declare nothing, so a line of prose cannot open a declaration. */
    private fun withoutComments(text: String): String =
        text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    /** The source at [relative] under this module, so the scan follows the module and not one machine. */
    private fun sourceOf(relative: String): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/$relative")
        assertTrue("the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** An android reference, which the binding must not contain. */
    private val CONTROL_WITH_ANDROID =
        """
        package dev.breaker.dictation.ui.render

        import android.view.View
        """.trimIndent()

    /** Three declarations offered to an app, where the entry file may offer two. */
    private val CONTROL_WITH_THREE_OFFERED =
        """
        package dev.breaker.dictation.ui

        fun createSettingsView(context: android.content.Context, settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View {
            return View(context)
        }

        fun createOnboardingView(context: android.content.Context, switch: BreakerSwitch, accessibilityServiceComponent: String): android.view.View {
            return View(context)
        }

        fun createOtherView(context: android.content.Context): android.view.View {
            return View(context)
        }
        """.trimIndent()

    /** A settings view that takes any object, which is not the declaration this module offers. */
    private val CONTROL_WEAK_PARAMETER =
        "fun createSettingsView(context: android.content.Context, settings: Any): android.view.View"

    /** A setup view that takes any object for its switch, which is not the declaration this module offers. */
    private val CONTROL_WEAK_SWITCH =
        "fun createOnboardingView(context: android.content.Context, switch: Any, accessibilityServiceComponent: String): android.view.View"

    /** A history view that takes any object for its store, which is not the declaration this module offers. */
    private val CONTROL_WEAK_HISTORY =
        "fun createHistoryView(context: android.content.Context, history: Any): android.view.View"
}