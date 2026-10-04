package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings handler: what each intent stores, what each failure says, and what the screen is rebuilt from. */
class SettingsIntentHandlerTest {
    private val darkLight = NONDEFAULT.copy(themeMode = StoredThemeMode.DARK)

    /** A handler over a store holding [held], with the phone in light mode. */
    private class Fixture(val held: AppSettings, val phoneDark: Boolean = false) {
        val store = FakeSettingsStore(held)
        val themes = ThemeController(store, systemIsDark = phoneDark)
        val handler = SettingsIntentHandler(store, themes, SettingsScreen())
    }

    /**
     * The text of the first node with this id, or null when the tree has no such node.
     *
     * Every node that carries text is read, not only a [Label]: the three switches
     * are [Action]s, so a reader blind to them reported no text for a row the screen
     * does draw, and could not tell a wrong value from a missing row.
     */
    private fun Rendered.textOf(id: String): String? =
        screen.nodes.flatMap { it.flatten() }
            .firstOrNull { node -> node.id == id && node.textOrNull() != null }
            ?.textOrNull()

    /** The text a node paints, or null for a node that paints none. */
    private fun Node.textOrNull(): String? = when (this) {
        is Label -> text
        is Action -> text
        else -> null
    }

    /** Every id in the tree, so a missing row is visible as a missing id. */
    private fun Rendered.ids(): List<String> = screen.nodes.flatMap { it.flatten() }.map(Node::id)

    private class Row(
        val intent: ScreenIntent,
        val stored: AppSettings,
    )

    /**
     * Written out here, apart from the code: each intent and the settings it must leave behind.
     *
     * Every row is built from [darkLight], the settings the [Fixture] is handed, because a
     * write changes one field and leaves every other as found. A row built from NONDEFAULT
     * would assert that the write also turned the scheme back to light, which no write does.
     */
    private val expected = listOf(
        // These two name the scheme themselves, so they agree with a NONDEFAULT baseline by coincidence.
        Row(ScreenIntent.ToggleTheme, darkLight.copy(themeMode = StoredThemeMode.LIGHT)),
        Row(ScreenIntent.UseSystemTheme, darkLight.copy(themeMode = StoredThemeMode.SYSTEM)),
        Row(ScreenIntent.SetRoutingMode("LOCAL"), darkLight.copy(mode = SttMode.LOCAL)),
        Row(ScreenIntent.SetSetting("preloadModel", "true"), darkLight.copy(preloadModel = true)),
    )

    @Test
    fun `each intent leaves exactly the settings it asks for and touches nothing else`() {
        assertEquals(4, expected.size)
        for (row in expected) {
            val fixture = Fixture(darkLight)
            fixture.handler.handle(row.intent)
            assertEquals("stored after ${row.intent}", row.stored, fixture.store.current)
            assertEquals(1, fixture.store.saveAttempts)
            assertEquals(listOf(row.stored), fixture.store.saves)
            assertNull("no message after ${row.intent}", noticeIn(fixture.handler.current()))
        }
    }

    @Test
    fun `a save that throws leaves the stored settings and the last save alone and says it could not be saved`() {
        val fixture = Fixture(darkLight)
        fixture.store.failSave = true
        for (row in expected) {
            val attemptsBefore = fixture.store.saveAttempts
            fixture.handler.handle(row.intent)
            assertTrue("the save was never reached for ${row.intent}", fixture.store.saveAttempts > attemptsBefore)
            assertEquals(Notice.COULD_NOT_SAVE, noticeIn(fixture.handler.current()))
            assertEquals("stored after ${row.intent}", darkLight, fixture.store.current)
            assertEquals(emptyList<AppSettings>(), fixture.store.saves)
        }
    }

    @Test
    fun `a load that throws saves nothing and says the settings could not be read`() {
        val fixture = Fixture(darkLight)
        fixture.store.failSave = true
        fixture.handler.handle(ScreenIntent.SetSetting("preloadModel", "true"))
        fixture.store.failLoad = true
        val savesBefore = fixture.store.saveAttempts
        for (row in expected) {
            val loadsBefore = fixture.store.loadAttempts
            fixture.handler.handle(row.intent)
            assertTrue("the load was never reached for ${row.intent}", fixture.store.loadAttempts > loadsBefore)
            assertEquals(Notice.COULD_NOT_READ, noticeIn(fixture.handler.current()))
            assertEquals("stored after ${row.intent}", darkLight, fixture.store.current)
        }
        assertEquals("a load that throws saves nothing", savesBefore, fixture.store.saveAttempts)
    }

    @Test
    fun `a value the screen will not store is refused with nothing read and nothing written`() {
        val fixture = Fixture(darkLight)
        val loadsBefore = fixture.store.loadAttempts
        fixture.handler.handle(ScreenIntent.SetSetting("preloadModel", "yes"))
        assertEquals(Notice.NOT_ACCEPTED, noticeIn(fixture.handler.current()))
        assertEquals("the store must not be touched", loadsBefore, fixture.store.loadAttempts)
        assertEquals(0, fixture.store.saveAttempts)
        assertEquals(darkLight, fixture.store.current)
    }

    @Test
    fun `the next success clears the message left by the failure before it`() {
        val fixture = Fixture(darkLight)
        fixture.store.failSave = true
        fixture.handler.handle(ScreenIntent.SetSetting("preloadModel", "true"))
        assertEquals(Notice.COULD_NOT_SAVE, noticeIn(fixture.handler.current()))
        fixture.store.failSave = false
        fixture.handler.handle(ScreenIntent.SetSetting("preloadModel", "true"))
        assertNull("the message must not stick", noticeIn(fixture.handler.current()))
        assertEquals(darkLight.copy(preloadModel = true), fixture.store.current)
    }

    @Test
    fun `the screen and the scheme are built from the state after the write not before it`() {
        val fixture = Fixture(darkLight)
        val before = fixture.handler.current()
        assertEquals("Scheme: dark, chosen here", before.textOf("settings.theme"))
        val after = fixture.handler.handle(ScreenIntent.ToggleTheme)
        assertEquals("Scheme: light, chosen here", after.textOf("settings.theme"))
        assertEquals(ShownThemeMode.LIGHT, after.theme.mode)
        assertEquals(Themes.of(ShownThemeMode.LIGHT).palette.bg, after.theme.color(PaletteSlot.BACKGROUND))
        assertEquals("Preload model: off", after.textOf("settings.preloadModel"))
    }

    @Test
    fun `a settings change is on the screen that is handed back`() {
        val fixture = Fixture(darkLight)
        val after = fixture.handler.handle(ScreenIntent.SetRoutingMode("LOCAL"))
        assertEquals("Mode: On this phone", after.textOf("settings.routing"))
        assertEquals(SttMode.LOCAL, fixture.store.current.mode)
    }

    @Test
    fun `a routing mode already in force is not written again and leaves no message`() {
        val fixture = Fixture(NONDEFAULT)
        val loadsBefore = fixture.store.loadAttempts
        val result = fixture.handler.handle(ScreenIntent.SetRoutingMode("SERVER"))
        assertTrue("the read was never reached", fixture.store.loadAttempts > loadsBefore)
        assertEquals(0, fixture.store.saveAttempts)
        assertNull(noticeIn(result))
        assertEquals(NONDEFAULT, fixture.store.current)
    }

    @Test
    fun `a handler made while the settings cannot be read draws no value row and never writes`() {
        val store = FakeSettingsStore(NONDEFAULT)
        store.failLoad = true
        val themes = ThemeController(store, systemIsDark = false)
        val handler = SettingsIntentHandler(store, themes, SettingsScreen())
        val result = handler.current()
        assertEquals(Notice.COULD_NOT_READ, noticeIn(result))
        assertEquals(0, store.saveAttempts)
        assertFalse("no value row may be drawn from settings that were not read", result.ids().contains("settings.modelSize"))
        assertFalse(result.ids().contains("settings.serverUrl"))
        assertTrue("the theme rows do not need the settings", result.ids().contains("settings.theme"))
        assertNotNull(result.textOf("settings.theme"))
        store.failLoad = false
        val after = handler.handle(ScreenIntent.SetSetting("preloadModel", "true"))
        assertNull("the message must go once an action succeeds", noticeIn(after))
        assertEquals(NONDEFAULT.copy(preloadModel = true), store.current)
        assertEquals("Preload model: on", after.textOf("settings.preloadModel"))
    }

    @Test
    fun `the handler names every intent so none can be added without a branch`() {
        val source = handlerSource()
        val branches = source.lines().count { it.trim().startsWith("ScreenIntent.") || "is ScreenIntent." in it }
        assertTrue("the branch on every intent was not found", branches >= 4)
        assertFalse("a catch-all branch would swallow an intent added later", hasCatchAllBranch(source))
        assertTrue("the control must fire on a branch that catches everything", hasCatchAllBranch(CONTROL_SOURCE))
    }

    /** Whether a `when` over intents has a branch that takes everything left over. */
    private fun hasCatchAllBranch(text: String): Boolean =
        withoutComments(text).lines().any { it.trimStart().startsWith("else ->") }

    /** Comments do not branch, so a line of prose about one must not be read as a branch. */
    private fun withoutComments(text: String): String =
        text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    /** The handler's own source, so a branch the code does not need cannot be added unnoticed. */
    private fun handlerSource(): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/screen/settings/SettingsIntentHandler.kt")
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

    /**
     * The message in the tree, or null when there is none.
     *
     * A message that is on the screen but is none of the fixed texts fails here
     * rather than passing as the absence of a message.
     */
    private fun noticeIn(result: Rendered): Notice? {
        val shown = result.screen.nodes.flatMap { it.flatten() }.filterIsInstance<Label>().firstOrNull { it.id == "settings.notice" }
            ?: return null
        return Notice.entries.firstOrNull { it.text == shown.text }
            ?: error("the message on screen is not one of the fixed texts: ${shown.text}")
    }
}
