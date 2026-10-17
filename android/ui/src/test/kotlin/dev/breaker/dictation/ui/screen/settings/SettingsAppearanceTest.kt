package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentCell
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two segmented rows of the settings screen: the appearance cells and the routing cells.
 * The selected cell is the stored choice, and each cell asks for the mode its own name gives.
 */
class SettingsAppearanceTest {
    private val screen = SettingsScreen()

    private fun themeOf(
        stored: StoredThemeMode = StoredThemeMode.LIGHT,
        shown: ShownThemeMode = ShownThemeMode.LIGHT,
    ): ThemeState = ThemeState(stored, shown, loaded = true)

    private fun cellsOf(s: Screen, id: String): List<SegmentCell> {
        val row = s.nodes.flatMap { it.flatten() }.firstOrNull { it.id == id } as? SegmentedRow
        return row?.cells ?: throw AssertionError("no segmented row with id $id")
    }

    @Test
    fun `each appearance cell carries its own theme mode name and its words`() {
        val s = screen.render(NONDEFAULT, themeOf(), null)
        val cells = cellsOf(s, "settings.theme.cells")
        assertEquals(
            listOf(
                "settings.theme.system" to ScreenIntent.SetThemeMode("SYSTEM"),
                "settings.theme.light" to ScreenIntent.SetThemeMode("LIGHT"),
                "settings.theme.dark" to ScreenIntent.SetThemeMode("DARK"),
            ),
            cells.map { it.id to it.intent },
        )
        assertEquals(listOf("System", "Light", "Dark"), cells.map { it.text })
    }

    @Test
    fun `the theme block offers three choices and marks the stored one as selected`() {
        val pairs = listOf(
            StoredThemeMode.LIGHT to ShownThemeMode.LIGHT,
            StoredThemeMode.LIGHT to ShownThemeMode.DARK,
            StoredThemeMode.DARK to ShownThemeMode.DARK,
            StoredThemeMode.DARK to ShownThemeMode.LIGHT,
            StoredThemeMode.SYSTEM to ShownThemeMode.DARK,
            StoredThemeMode.SYSTEM to ShownThemeMode.LIGHT,
        )
        for ((stored, shown) in pairs) {
            val s = screen.render(NONDEFAULT, ThemeState(stored, shown, loaded = true), null)
            val cells = cellsOf(s, "settings.theme.cells")
            assertEquals("three choices with stored $stored shown $shown", 3, cells.size)
            assertEquals(
                "selected with stored $stored shown $shown",
                listOf(ScreenIntent.SetThemeMode(stored.name)),
                cells.filter { it.selected }.map { it.intent },
            )
        }
    }

    @Test
    fun `the light and dark cells ask for their own mode whatever scheme is shown`() {
        for (shown in ShownThemeMode.entries) {
            val s = screen.render(NONDEFAULT, themeOf(StoredThemeMode.SYSTEM, shown), null)
            val cells = cellsOf(s, "settings.theme.cells").associateBy { it.id }
            assertEquals(
                "light cell with shown $shown",
                ScreenIntent.SetThemeMode("LIGHT"),
                cells.getValue("settings.theme.light").intent,
            )
            assertEquals(
                "dark cell with shown $shown",
                ScreenIntent.SetThemeMode("DARK"),
                cells.getValue("settings.theme.dark").intent,
            )
        }
    }

    @Test
    fun `all three routing modes are offered and the mode in force is the selected one`() {
        for (mode in SttMode.entries) {
            val s = screen.render(NONDEFAULT.copy(mode = mode), themeOf(), null)
            val cells = cellsOf(s, "settings.routing.cells")
            assertEquals(
                "ids with $mode in force",
                listOf("settings.routing.AUTO", "settings.routing.LOCAL", "settings.routing.SERVER"),
                cells.map { it.id },
            )
            assertEquals("words with $mode in force", listOf("Automatic", "Phone", "Server"), cells.map { it.text })
            assertEquals(
                "intents with $mode in force",
                listOf(
                    ScreenIntent.SetRoutingMode("AUTO"),
                    ScreenIntent.SetRoutingMode("LOCAL"),
                    ScreenIntent.SetRoutingMode("SERVER"),
                ),
                cells.map { it.intent },
            )
            assertEquals(
                "selected with $mode in force",
                listOf("settings.routing.${mode.name}"),
                cells.filter { it.selected }.map { it.id },
            )
        }
    }
}
