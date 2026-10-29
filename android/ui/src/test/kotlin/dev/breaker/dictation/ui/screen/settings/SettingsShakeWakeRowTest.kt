package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.ShakeWakeMode
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentCell
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shake to wake row and the keyboard tile switch on the settings screen: the three
 * cells and their words, the key and the mode name each cell asks for, the label and
 * help line, and where the keyboard switch sits and what it asks for.
 */
class SettingsShakeWakeRowTest {
    private val screen = SettingsScreen()

    private val cellWords = listOf("Always", "Keyboard only", "Off")
    private val cellIds = listOf("settings.shakeWake.ALWAYS", "settings.shakeWake.KEYBOARD_ONLY", "settings.shakeWake.OFF")
    private val cellNames = listOf("ALWAYS", "KEYBOARD_ONLY", "OFF")

    private fun themeOf(): ThemeState = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, loaded = true)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun node(s: Screen, id: String): Node =
        nodesOf(s).firstOrNull { it.id == id } ?: throw AssertionError("no node with id $id")

    private fun labelOf(s: Screen, id: String): Label =
        node(s, id) as? Label ?: throw AssertionError("no Label with id $id")

    private fun switchOf(s: Screen, id: String): SwitchRow =
        node(s, id) as? SwitchRow ?: throw AssertionError("no SwitchRow with id $id")

    private fun cellsOf(s: Screen, id: String): List<SegmentCell> =
        (node(s, id) as? SegmentedRow)?.cells ?: throw AssertionError("no SegmentedRow with id $id")

    private fun childIds(s: Screen, id: String): List<String> = (node(s, id) as Box).children.map { it.id }

    @Test
    fun `the shake to wake row holds three cells in the order Always, Keyboard only, Off for every stored mode`() {
        for (stored in ShakeWakeMode.entries) {
            val cells = cellsOf(screen.render(NONDEFAULT.copy(shakeWakeMode = stored), themeOf(), null), "settings.shakeWake.cells")
            assertEquals("cell words with stored $stored", cellWords, cells.map { it.text })
            assertEquals("cell ids with stored $stored", cellIds, cells.map { it.id })
        }
    }

    @Test
    fun `exactly one shake to wake cell is selected and it is the stored mode`() {
        for (stored in ShakeWakeMode.entries) {
            val cells = cellsOf(screen.render(NONDEFAULT.copy(shakeWakeMode = stored), themeOf(), null), "settings.shakeWake.cells")
            assertEquals("selected cells with stored $stored", 1, cells.count { it.selected })
            assertEquals(
                "selected id with stored $stored",
                listOf("settings.shakeWake." + stored.name),
                cells.filter { it.selected }.map { it.id },
            )
        }
    }

    @Test
    fun `each shake to wake cell asks for the shake key with its own mode name`() {
        val cells = cellsOf(screen.render(NONDEFAULT, themeOf(), null), "settings.shakeWake.cells")
        assertEquals(
            cellNames.map { ScreenIntent.SetSetting("shakeWakeMode", it) },
            cells.map { it.intent },
        )
    }

    @Test
    fun `the shake to wake label and help line carry the exact sentences`() {
        val s = screen.render(NONDEFAULT, themeOf(), null)
        val label = labelOf(s, "settings.shakeWake.label")
        assertEquals("Shake to wake", label.text)
        assertEquals(TypeRole.BODY, label.role)
        assertEquals(PaletteSlot.TEXT, label.color)
        val help = labelOf(s, "settings.shakeWake.help")
        assertEquals(
            "Always: shake the phone any time to bring the tile back. " +
                "Keyboard only: shaking works only while the keyboard is open. Off: shaking does nothing.",
            help.text,
        )
        assertEquals(PaletteSlot.TEXT_MUTED, help.color)
    }

    @Test
    fun `the keyboard switch sits under the shake row and carries its key and the stored value`() {
        for (current in listOf(false, true)) {
            val s = screen.render(NONDEFAULT.copy(showTileOnKeyboard = current), themeOf(), null)
            // The keyboard switch comes two children after the shake row: the hairline sits between them.
            val children = childIds(s, "settings.switches")
            assertEquals(
                "the keyboard switch two children after the shake row with stored $current",
                children.indexOf("settings.shakeWake") + 2,
                children.indexOf("settings.showTileOnKeyboard"),
            )
            val row = switchOf(s, "settings.showTileOnKeyboard")
            assertEquals("state with stored $current", current, row.on)
            assertEquals(
                "intent with stored $current",
                ScreenIntent.SetSetting("showTileOnKeyboard", (!current).toString()),
                row.intent,
            )
        }
    }

    @Test
    fun `the keyboard switch carries its label and help line`() {
        val row = switchOf(screen.render(NONDEFAULT, themeOf(), null), "settings.showTileOnKeyboard")
        assertEquals("Show the tile when the keyboard opens", row.label)
        assertEquals("The tile comes up by itself when a keyboard opens over a text field.", row.help)
    }
}
