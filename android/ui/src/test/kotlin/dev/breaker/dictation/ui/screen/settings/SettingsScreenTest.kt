package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentCell
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings screen draws: every value comes from the settings handed in, no value it must not show
 * appears anywhere, and nothing at all is drawn for settings that have never been read.
 *
 * The look of the rows is in SettingsLookTest and SettingsAppearanceTest; the accepted changes are in
 * SettingsScreenEditTest.
 */
class SettingsScreenTest {
    private val screen = SettingsScreen()

    private fun themeOf(
        stored: StoredThemeMode = StoredThemeMode.LIGHT,
        shown: ShownThemeMode = ShownThemeMode.LIGHT,
        loaded: Boolean = true,
    ) = ThemeState(stored, shown, loaded)

    /** Every node of [s], parents before children. */
    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun node(s: Screen, id: String): Node? = nodesOf(s).firstOrNull { it.id == id }

    private fun labelText(s: Screen, id: String): String? = (node(s, id) as? Label)?.text

    private fun fieldText(s: Screen, id: String): String? = (node(s, id) as? ValueField)?.text

    private fun switchOf(s: Screen, id: String): SwitchRow = node(s, id) as? SwitchRow
        ?: throw AssertionError("no SwitchRow with id $id in ${nodesOf(s).map { it.id }}")

    private fun cellsOf(s: Screen, id: String): List<SegmentCell> = (node(s, id) as? SegmentedRow)?.cells
        ?: throw AssertionError("no SegmentedRow with id $id")

    private fun everyStringIn(s: Screen): List<String> = nodesOf(s).flatMap { nodeStrings(it) }

    /** The id and text of a node, plus every string an intent carries. */
    private fun nodeStrings(node: Node): List<String> = when (node) {
        is Label -> listOf(node.id, node.text)
        is Action -> listOf(node.id, node.text) + intentStrings(node.intent)
        is SwitchRow -> listOf(node.id, node.label, node.help) + intentStrings(node.intent)
        is ValueField -> listOf(node.id, node.text)
        is SegmentedRow -> listOf(node.id) + node.cells.flatMap { listOf(it.id, it.text) + intentStrings(it.intent) }
        else -> listOf(node.id)
    }

    private fun intentStrings(intent: ScreenIntent): List<String> = when (intent) {
        is ScreenIntent.SetSetting -> listOf(intent.key, intent.value)
        is ScreenIntent.SetRoutingMode -> listOf(intent.mode)
        is ScreenIntent.SetThemeMode -> listOf(intent.mode)
        is ScreenIntent.Setup -> listOf(intent.action)
        is ScreenIntent.History -> listOf(intent.action, intent.id)
        is ScreenIntent.Home -> listOf(intent.action)
        ScreenIntent.ToggleTheme, ScreenIntent.UseSystemTheme -> emptyList()
    }

    @Test
    fun `every displayed value comes from the settings that were handed in`() {
        val s = screen.render(NONDEFAULT, themeOf(), notice = null)
        assertEquals("de, medium", labelText(s, "settings.model.value"))
        assertEquals(NONDEFAULT.serverUrl, fieldText(s, "settings.server.field"))
        assertEquals(listOf("settings.routing.SERVER"), cellsOf(s, "settings.routing.cells").filter { it.selected }.map { it.id })
        assertFalse(switchOf(s, "settings.preloadModel").on)
        assertEquals("set", labelText(s, "settings.apiKey.value"))
    }

    @Test
    fun `the key reference appears in no id, no text and no intent anywhere`() {
        // Two references of different shape, so a screen that prints one of them
        // and not the other still fails here.
        val references = listOf(NONDEFAULT.apiKeyRef.orEmpty(), "s3cret-ref-4417")
        val followsPhone = themeOf(StoredThemeMode.SYSTEM, ShownThemeMode.DARK)
        for (reference in references) {
            val held = NONDEFAULT.copy(apiKeyRef = reference)
            val s = screen.render(held, followsPhone, Notice.NOT_ACCEPTED)
            val strings = everyStringIn(s)
            assertTrue(
                "the reference $reference appeared in $strings",
                strings.none { it.contains(reference) },
            )
        }
    }

    @Test
    fun `each switch asks for its own key at the opposite of its current value`() {
        val cases = listOf(
            "settings.preloadModel" to "preloadModel",
            "settings.wakeGestureEnabled" to "wakeGestureEnabled",
            "settings.formattingEnabled" to "formattingEnabled",
        )
        for (current in listOf(false, true)) {
            val held = NONDEFAULT.copy(
                preloadModel = current,
                wakeGestureEnabled = current,
                formattingEnabled = current,
            )
            val s = screen.render(held, themeOf(), null)
            for ((id, key) in cases) {
                val row = switchOf(s, id)
                val asked = ScreenIntent.SetSetting(key, (!current).toString())
                assertEquals("id $id with current $current", asked, row.intent)
                assertEquals("the state drawn on $id with current $current", current, row.on)
            }
        }
    }

    @Test
    fun `the tile position and every other id stay out of the tree, and ids are unique`() {
        val s = screen.render(NONDEFAULT, themeOf(), null)
        val every = nodesOf(s)
        val labels = every.filterIsInstance<Label>().map { it.text }
        val buttons = every.filterIsInstance<Action>().map { it.text }
        val texts = labels + buttons
        val position = NONDEFAULT.tilePosition
        for (fragment in listOf("0.2", "0.8", position.x.toString(), position.y.toString())) {
            assertTrue(
                "tile position fragment $fragment appeared in $texts",
                texts.none { it.contains(fragment) },
            )
        }
        val ids = every.map { it.id }
        assertEquals("duplicate node ids in $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `a notice is one label carrying exactly its own text in the danger slot`() {
        for (notice in Notice.entries) {
            val s = screen.render(NONDEFAULT, themeOf(), notice)
            val label = node(s, "settings.notice") as? Label
                ?: throw AssertionError("no notice label for $notice")
            assertEquals(notice.text, label.text)
            assertEquals(TypeRole.BODY, label.role)
            assertEquals(PaletteSlot.DANGER, label.color)
            assertEquals(1, nodesOf(s).count { it.id == "settings.notice" })
            // Directly after the stripe, so a reader meets it before any row.
            val ids = nodesOf(s).map { it.id }
            assertEquals("settings.stripe", ids[ids.indexOf("settings.notice") - 1])
        }
        val without = screen.render(NONDEFAULT, themeOf(), notice = null)
        assertNull(node(without, "settings.notice"))
    }

    @Test
    fun `settings that were never read draw the title and the theme block and no value`() {
        val unread = ThemeState(StoredThemeMode.SYSTEM, ShownThemeMode.DARK, loaded = false)
        val s = screen.render(null, unread, Notice.COULD_NOT_READ)
        assertEquals("Settings", s.title)
        val ids = nodesOf(s).map { it.id }
        assertTrue("the theme block is drawn", "settings.theme" in ids)
        val appearance = cellsOf(s, "settings.theme.cells")
        assertEquals(
            listOf(ScreenIntent.SetThemeMode("SYSTEM")),
            appearance.filter { it.selected }.map { it.intent },
        )
        for (absent in listOf(
            "settings.routing",
            "settings.switches",
            "settings.values",
            "settings.model.value",
            "settings.server.field",
            "settings.apiKey.value",
            "settings.preloadModel",
            "settings.wakeGestureEnabled",
            "settings.formattingEnabled",
        )) {
            assertTrue("$absent is drawn from settings that were never read", absent !in ids)
        }
        // No default value is shown in place of a real one.
        val defaults = AppSettings()
        for (defaultText in listOf(defaults.modelSize, defaults.language, defaults.mode.name)) {
            assertTrue(
                "a default value $defaultText appeared in the tree",
                everyStringIn(s).none { it == defaultText },
            )
        }
    }
}
