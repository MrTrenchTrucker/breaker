package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The look of the settings screen: the blocks and their order, the words and colours of
 * every heading and help line, the value rows, the switch rows, and the absence of the
 * words the screen no longer shows.
 */
class SettingsLookTest {
    private val screen = SettingsScreen()

    private fun themeOf(): ThemeState = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, loaded = true)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun node(s: Screen, id: String): Node =
        nodesOf(s).firstOrNull { it.id == id } ?: throw AssertionError("no node with id $id")

    private fun labelOf(s: Screen, id: String): Label =
        node(s, id) as? Label ?: throw AssertionError("no Label with id $id")

    private fun switchOf(s: Screen, id: String): SwitchRow =
        node(s, id) as? SwitchRow ?: throw AssertionError("no SwitchRow with id $id")

    private fun fieldOf(s: Screen, id: String): ValueField =
        node(s, id) as? ValueField ?: throw AssertionError("no ValueField with id $id")

    private fun childIds(s: Screen, id: String): List<String> = (node(s, id) as Box).children.map { it.id }

    /** Every text a node of [s] paints: labels, actions, switch words, field text and segment cells. */
    private fun textsOf(s: Screen): List<String> = nodesOf(s).flatMap { node ->
        when (node) {
            is Label -> listOf(node.text)
            is Action -> listOf(node.text)
            is SwitchRow -> listOf(node.label, node.help)
            is ValueField -> listOf(node.text)
            is SegmentedRow -> node.cells.map { it.text }
            else -> emptyList()
        }
    }

    @Test
    fun `the blocks come in the agreed order and each block holds its rows in order`() {
        val s = screen.render(NONDEFAULT, themeOf(), notice = null)
        assertEquals(
            listOf(
                "settings.titlebar", "settings.rule", "settings.gap.head", "settings.theme",
                "settings.gap.routing", "settings.routing", "settings.gap.switches", "settings.switches",
                "settings.gap.values", "settings.values", "settings.stripe",
            ),
            s.nodes.map { it.id },
        )
        assertEquals(
            listOf("settings.theme.heading", "settings.theme.cells", "settings.theme.help"),
            childIds(s, "settings.theme"),
        )
        assertEquals(
            listOf("settings.routing.heading", "settings.routing.cells", "settings.routing.help"),
            childIds(s, "settings.routing"),
        )
        assertEquals(
            listOf(
                "settings.switches.heading", "settings.preloadModel", "settings.hairline.preload",
                "settings.shakeWake", "settings.hairline.wake", "settings.showTileOnKeyboard",
                "settings.hairline.keyboard", "settings.formattingEnabled",
            ),
            childIds(s, "settings.switches"),
        )
        assertEquals(
            listOf("settings.values.heading", "settings.model", "settings.server", "settings.apiKey"),
            childIds(s, "settings.values"),
        )
    }

    @Test
    fun `each heading and help line carries its agreed words and colour`() {
        val s = screen.render(NONDEFAULT, themeOf(), notice = null)
        assertEquals("Appearance", labelOf(s, "settings.theme.heading").text)
        assertEquals(TypeRole.DISPLAY, labelOf(s, "settings.theme.heading").role)
        assertEquals(PaletteSlot.TEXT_MUTED, labelOf(s, "settings.theme.heading").color)
        assertEquals("System matches your phone's light or dark setting.", labelOf(s, "settings.theme.help").text)
        assertEquals(PaletteSlot.TEXT_MUTED, labelOf(s, "settings.theme.help").color)
        assertEquals("Where speech turns into text", labelOf(s, "settings.routing.heading").text)
        assertEquals(
            "Automatic uses the server when it answers, and the phone when it does not.",
            labelOf(s, "settings.routing.help").text,
        )
        assertEquals("Dictation", labelOf(s, "settings.switches.heading").text)
        assertEquals("Model and server", labelOf(s, "settings.values.heading").text)
        assertEquals("Server address", labelOf(s, "settings.server.label").text)
        assertEquals("API key", labelOf(s, "settings.apiKey.label").text)
    }

    @Test
    fun `the model row names the language by its name and the model size`() {
        val english = screen.render(NONDEFAULT.copy(language = "en", modelSize = "small"), themeOf(), null)
        assertEquals(SetupTexts.HEADING_MODEL, labelOf(english, "settings.model.label").text)
        assertEquals("English, small", labelOf(english, "settings.model.value").text)
        val other = screen.render(NONDEFAULT, themeOf(), null)
        assertEquals("de, medium", labelOf(other, "settings.model.value").text)
        val head = node(other, "settings.model.head") as Box
        assertTrue(head.horizontal && head.spread)
        assertFalse((node(other, "settings.model") as Box).horizontal)
    }

    @Test
    fun `a server address of only spaces reads the same as an empty one`() {
        for (blank in listOf("", "   ")) {
            val field = fieldOf(screen.render(NONDEFAULT.copy(serverUrl = blank), themeOf(), null), "settings.server.field")
            assertEquals("blank serverUrl $blank", "(not set)", field.text)
            assertTrue("blank serverUrl $blank is muted", field.muted)
        }
        val given = fieldOf(screen.render(NONDEFAULT, themeOf(), null), "settings.server.field")
        assertEquals(NONDEFAULT.serverUrl, given.text)
        assertFalse(given.muted)
    }

    @Test
    fun `the key row says only whether a key is held`() {
        val held = screen.render(NONDEFAULT, themeOf(), null)
        assertEquals("set", labelOf(held, "settings.apiKey.value").text)

        // An empty reference is still a reference: the key is held.
        val emptyRef = screen.render(NONDEFAULT.copy(apiKeyRef = ""), themeOf(), null)
        assertEquals("set", labelOf(emptyRef, "settings.apiKey.value").text)
        val noRef = screen.render(NONDEFAULT.copy(apiKeyRef = null), themeOf(), null)
        assertEquals("not set", labelOf(noRef, "settings.apiKey.value").text)
    }

    @Test
    fun `each switch row carries its label help state and the opposite intent`() {
        val cases = listOf(
            Triple("settings.preloadModel", "Preload model", "preloadModel"),
            Triple("settings.showTileOnKeyboard", "Show the tile when the keyboard opens", "showTileOnKeyboard"),
            Triple("settings.formattingEnabled", "Formatting", "formattingEnabled"),
        )
        for (current in listOf(false, true)) {
            val held = NONDEFAULT.copy(
                preloadModel = current,
                showTileOnKeyboard = current,
                formattingEnabled = current,
            )
            val s = screen.render(held, themeOf(), null)
            for ((id, label, key) in cases) {
                val row = switchOf(s, id)
                assertEquals("label of $id", label, row.label)
                assertEquals("state of $id with current $current", current, row.on)
                assertEquals(
                    "intent of $id with current $current",
                    ScreenIntent.SetSetting(key, (!current).toString()),
                    row.intent,
                )
            }
            assertEquals("Keeps the model ready for a faster start.", switchOf(s, "settings.preloadModel").help)
            assertEquals(
                "The tile comes up by itself when a keyboard opens over a text field.",
                switchOf(s, "settings.showTileOnKeyboard").help,
            )
            assertEquals("Spoken lists become lists. Filler words go.", switchOf(s, "settings.formattingEnabled").help)
        }
    }

    @Test
    fun `no retired words appear in any label switch field or cell text`() {
        val texts = textsOf(screen.render(NONDEFAULT, themeOf(), null))
        val retired = listOf(
            "Scheme:", "Use the dark scheme", "Use the light scheme", "Follow the phone",
            "following the phone", "chosen here", "On this phone", "On the server",
            "Mode: ", "Language: ", "Model: ", "Server: ", "API key: ", ": on", ": off", "Wake gesture",
        )
        for (text in texts) {
            for (word in retired) {
                assertFalse("retired '$word' in '$text'", text.contains(word))
            }
        }
        assertFalse("the old routing heading", texts.contains("Routing"))
    }
}
