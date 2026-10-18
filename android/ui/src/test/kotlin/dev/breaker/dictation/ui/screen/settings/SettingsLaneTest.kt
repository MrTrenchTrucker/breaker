package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.controls.ControlTexts
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.BackArrow
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Rule
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.Spacer
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.screen.titleBar
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings title bar, the speech model row and the spacing between the blocks.
 */
class SettingsLaneTest {
    private val screen = SettingsScreen()

    private fun render(model: ModelRow): Screen =
        screen.render(NONDEFAULT, ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, loaded = true), null, model)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun node(s: Screen, id: String): Node? = nodesOf(s).firstOrNull { it.id == id }

    @Test
    fun `the title bar is the first two nodes with a back arrow and an accent rule`() {
        val nodes = render(ModelRow.READY).nodes
        assertEquals(listOf("settings.titlebar", "settings.rule"), nodes.take(2).map { it.id })
        val bar = nodes[0] as Box
        assertEquals(ScreenIntent.Back, (bar.children[0] as BackArrow).intent)
        val title = bar.children[1] as Label
        assertEquals("Settings", title.text)
        assertEquals(TypeRole.DISPLAY, title.role)
        assertTrue(title.caps && title.italic)
        assertEquals(PaletteSlot.ACCENT, (nodes[1] as Rule).color)
    }

    @Test
    fun `the title bar takes the id prefix it is given`() {
        val nodes = titleBar("history", "History")
        assertEquals(listOf("history.titlebar", "history.rule"), nodes.map { it.id })
        assertEquals("history.back", ((nodes[0] as Box).children[0]).id)
        assertEquals("history.title", ((nodes[0] as Box).children[1]).id)
    }

    @Test
    fun `the brand stripes close the screen`() {
        assertEquals("settings.stripe", render(ModelRow.READY).nodes.last().id)
    }

    @Test
    fun `the blocks are set apart by spacers named by size and sit on the page background`() {
        val nodes = render(ModelRow.READY).nodes
        assertEquals(
            listOf(Space.L, Space.L, Space.L, Space.XL),
            nodes.filterIsInstance<Spacer>().map { it.size },
        )
        for (id in listOf("settings.theme", "settings.routing", "settings.switches", "settings.values")) {
            assertEquals("the background of $id", PaletteSlot.BACKGROUND, (node(render(ModelRow.READY), id) as Box).background)
        }
    }

    @Test
    fun `the ready badge shows the existing word in capitals and no download button`() {
        val s = render(ModelRow.READY)
        assertNull(node(s, "settings.model.download"))
        val badge = node(s, "settings.model.ready") as Box
        assertEquals(PaletteSlot.PRIMARY, badge.background)
        val word = badge.children.single() as Label
        assertEquals(ControlTexts.BADGE_READY, word.text)
        assertTrue(word.caps)
        assertEquals(PaletteSlot.ON, word.color)
    }

    @Test
    fun `the download button shows when the model is not ready with the existing words and the raised kit`() {
        val s = render(ModelRow(ready = false, line = null))
        assertNull(node(s, "settings.model.ready"))
        val button = node(s, SettingsIds.DOWNLOAD_ID) as Action
        assertEquals(SetupTexts.BUTTON_DOWNLOAD_MODEL, button.text)
        assertEquals(KitLook.RAISED, button.kit)
        assertEquals(ScreenIntent.Setup(SettingsIds.DOWNLOAD_ACTION), button.intent)
    }

    @Test
    fun `the download line is drawn under the button only while a download runs`() {
        val running = render(ModelRow(ready = false, line = "Checking the speech model."))
        assertEquals("Checking the speech model.", (node(running, "settings.model.line") as Label).text)
        assertNull(node(render(ModelRow(ready = false, line = null)), "settings.model.line"))
        assertNull(node(render(ModelRow(ready = true, line = "Checking the speech model.")), "settings.model.line"))
    }

    @Test
    fun `the screen offers no edit for the download intent, so only the host acts on it`() {
        assertNull(SettingsScreen().editFor(ScreenIntent.Setup(SettingsIds.DOWNLOAD_ACTION)))
    }

    @Test
    fun `the speech model head spreads its name to the left edge and the ready badge to the right edge`() {
        val head = node(render(ModelRow.READY), SettingsIds.MODEL_HEAD) as Box
        assertTrue(head.horizontal && head.spread)
        assertEquals(listOf(SettingsIds.MODEL_LABEL, SettingsIds.MODEL_READY), head.children.map { it.id })
    }

    @Test
    fun `the api key row spreads its label to the left edge and the word set to the right edge`() {
        val row = node(render(ModelRow.READY), SettingsIds.API_KEY_ROW) as Box
        assertTrue(row.horizontal && row.spread)
        assertEquals(listOf(SettingsIds.API_KEY_LABEL, SettingsIds.API_KEY_VALUE), row.children.map { it.id })
    }
}
