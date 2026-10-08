package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings screen draws: every value comes from the settings handed
 * in, no value it must not show appears anywhere, and nothing at all is drawn
 * for settings that have never been read.
 *
 * The accepted changes are in SettingsScreenEditTest.
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

    private fun action(s: Screen, id: String): Action = node(s, id) as? Action
        ?: throw AssertionError("no Action with id $id in ${nodesOf(s).map { it.id }}")

    private fun everyStringIn(s: Screen): List<String> = nodesOf(s).flatMap { nodeStrings(it) }

    /** The id and text of a node, plus every string an intent carries. */
    private fun nodeStrings(node: Node): List<String> {
        val own = when (node) {
            is Label -> listOf(node.id, node.text)
            is Action -> listOf(node.id, node.text) + intentStrings(node.intent)
            else -> listOf(node.id)
        }
        return own
    }

    private fun intentStrings(intent: ScreenIntent): List<String> = when (intent) {
        is ScreenIntent.SetSetting -> listOf(intent.key, intent.value)
        is ScreenIntent.SetRoutingMode -> listOf(intent.mode)
        is ScreenIntent.Setup -> listOf(intent.action)
        is ScreenIntent.History -> listOf(intent.action, intent.id)
        ScreenIntent.ToggleTheme, ScreenIntent.UseSystemTheme -> emptyList()
    }

    @Test
    fun `every displayed value comes from the settings that were handed in`() {
        val s = screen.render(NONDEFAULT, themeOf(), notice = null)
        assertEquals("Model: medium", labelText(s, "settings.modelSize"))
        assertEquals("Language: de", labelText(s, "settings.language"))
        assertEquals("Server: https://srv.example.invalid:8443", labelText(s, "settings.serverUrl"))
        assertEquals("Mode: On the server", labelText(s, "settings.routing"))
        assertEquals("Preload model: off", action(s, "settings.preloadModel").text)
        assertEquals("Wake gesture: off", action(s, "settings.wakeGestureEnabled").text)
        assertEquals("Formatting: off", action(s, "settings.formattingEnabled").text)
        assertEquals("API key: set", labelText(s, "settings.apiKey"))
    }

    @Test
    fun `a server address of only spaces reads the same as an empty one`() {
        for (blank in listOf("", "   ")) {
            val held = NONDEFAULT.copy(serverUrl = blank)
            val s = screen.render(held, themeOf(), notice = null)
            assertEquals("blank serverUrl $blank", "Server: (not set)", labelText(s, "settings.serverUrl"))
        }
        val given = screen.render(NONDEFAULT, themeOf(), notice = null)
        assertEquals("Server: " + NONDEFAULT.serverUrl, labelText(given, "settings.serverUrl"))
    }

    @Test
    fun `the key row says only whether a key is held`() {
        val held = screen.render(NONDEFAULT, themeOf(), null)
        assertEquals("API key: set", labelText(held, "settings.apiKey"))

        // An empty reference is still a reference: the key is held.
        val emptyRef = screen.render(NONDEFAULT.copy(apiKeyRef = ""), themeOf(), null)
        assertEquals("API key: set", labelText(emptyRef, "settings.apiKey"))
        val noRef = screen.render(NONDEFAULT.copy(apiKeyRef = null), themeOf(), null)
        assertEquals("API key: not set", labelText(noRef, "settings.apiKey"))
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
    fun `the theme block names the shown scheme and offers both theme choices`() {
        val cases = listOf(
            Triple(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, "Scheme: light, chosen here"),
            Triple(StoredThemeMode.LIGHT, ShownThemeMode.DARK, "Scheme: dark, chosen here"),
            Triple(StoredThemeMode.DARK, ShownThemeMode.DARK, "Scheme: dark, chosen here"),
            Triple(StoredThemeMode.DARK, ShownThemeMode.LIGHT, "Scheme: light, chosen here"),
            Triple(StoredThemeMode.SYSTEM, ShownThemeMode.DARK, "Scheme: dark, following the phone"),
            Triple(StoredThemeMode.SYSTEM, ShownThemeMode.LIGHT, "Scheme: light, following the phone"),
        )
        for ((stored, shown, expected) in cases) {
            val state = ThemeState(stored, shown, loaded = true)
            val s = screen.render(NONDEFAULT, state, null)
            assertEquals("stored $stored shown $shown", expected, labelText(s, "settings.theme"))
            assertEquals(ScreenIntent.ToggleTheme, action(s, "settings.themeToggle").intent)
            assertTrue("the toggle is always offered", action(s, "settings.themeToggle").enabled)
            val followPhone = action(s, "settings.themeSystem")
            assertEquals("Follow the phone", followPhone.text)
            assertEquals(ScreenIntent.UseSystemTheme, followPhone.intent)
            assertEquals(
                "stored $stored",
                stored != StoredThemeMode.SYSTEM,
                followPhone.enabled,
            )
        }
    }

    @Test
    fun `the toggle names the scheme it would switch to`() {
        val light = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, loaded = true)
        val toDark = screen.render(NONDEFAULT, light, null)
        assertEquals("Use the dark scheme", action(toDark, "settings.themeToggle").text)

        val dark = ThemeState(StoredThemeMode.DARK, ShownThemeMode.DARK, loaded = true)
        val toLight = screen.render(NONDEFAULT, dark, null)
        assertEquals("Use the light scheme", action(toLight, "settings.themeToggle").text)
    }

    @Test
    fun `all three routing modes are offered and only the current one is not`() {
        for (mode in SttMode.entries) {
            val s = screen.render(NONDEFAULT.copy(mode = mode), themeOf(), null)
            val actions = SttMode.entries.map { candidate ->
                val node = action(s, "settings.mode.${candidate.name}")
                assertEquals(
                    "intent for ${candidate.name} with $mode in force",
                    ScreenIntent.SetRoutingMode(candidate.name),
                    node.intent,
                )
                node
            }
            assertEquals(
                "exactly one mode is not offered with $mode in force",
                1,
                actions.count { !it.enabled },
            )
            assertFalse(
                "the mode in force is the one that is not offered, current $mode",
                action(s, "settings.mode.${mode.name}").enabled,
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
                val node = action(s, id)
                val asked = ScreenIntent.SetSetting(key, (!current).toString())
                assertEquals("id $id with current $current", asked, node.intent)
                assertEquals(
                    "the text of $id states the value drawn on it",
                    if (current) "on" else "off",
                    node.text.substringAfterLast(": "),
                )
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
        assertEquals("Scheme: dark, following the phone", labelText(s, "settings.theme"))
        assertEquals(ScreenIntent.ToggleTheme, action(s, "settings.themeToggle").intent)
        assertEquals(ScreenIntent.UseSystemTheme, action(s, "settings.themeSystem").intent)
        for (absent in listOf(
            "settings.modelSize",
            "settings.serverUrl",
            "settings.language",
            "settings.apiKey",
            "settings.routing",
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
