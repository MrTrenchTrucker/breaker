package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.controls.ControlTexts
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.model.ModelRatingText
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speech model section of the settings screen, drawn from one row per offered model. With no rows the
 * one-button row is drawn as before; with rows, each model has the controls its state allows, and each control
 * reports its prefixed action with the model's id. Hand-traced only: nothing here has been compiled or run.
 */
class SettingsModelRowsTest {
    private val screen = SettingsScreen()

    private val small = ModelRowItem(
        id = "small", label = "Good", rating = 1, sizeMb = 349, installed = false, inUse = false, line = null,
    )

    private val parakeet = ModelRowItem(
        id = "parakeet", label = "Better", rating = 2, sizeMb = 465, installed = false, inUse = false, line = null,
    )

    private fun theme(): ThemeState = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, loaded = true)

    private fun render(vararg rows: ModelRowItem): Screen =
        screen.render(NONDEFAULT, theme(), null, ModelRow(ready = false, line = null, rows = rows.toList()))

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun ids(s: Screen): List<String> = nodesOf(s).map { it.id }

    private fun action(s: Screen, id: String): Action =
        nodesOf(s).firstOrNull { it.id == id } as? Action ?: throw AssertionError("no Action with id $id in ${ids(s)}")

    private fun label(s: Screen, id: String): Label =
        nodesOf(s).firstOrNull { it.id == id } as? Label ?: throw AssertionError("no Label with id $id")

    @Test
    fun `with no rows the model row keeps its one download button and no model controls`() {
        val s = screen.render(NONDEFAULT, theme(), null, ModelRow(ready = false, line = null))
        assertTrue("settings.model.download" in ids(s))
        assertFalse(ids(s).any { it.startsWith("settings.model.small.") || it.startsWith("settings.model.parakeet.") })
    }

    @Test
    fun `a model that is not on the phone draws its download button and no delete and no use`() {
        val s = render(small)
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "small"), action(s, "settings.model.small.download").intent)
        assertFalse("settings.model.small.delete" in ids(s))
        assertFalse("settings.model.small.use" in ids(s))
        assertFalse("settings.model.small.ready" in ids(s))
    }

    @Test
    fun `an installed model not in use draws delete and use this one and no download`() {
        val s = render(parakeet.copy(installed = true))
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DELETE, "parakeet"), action(s, "settings.model.parakeet.delete").intent)
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_USE, "parakeet"), action(s, "settings.model.parakeet.use").intent)
        assertFalse("settings.model.parakeet.download" in ids(s))
        assertFalse("settings.model.parakeet.ready" in ids(s))
    }

    @Test
    fun `the model in use shows the ready word and has no delete and no use and no download`() {
        val s = render(parakeet.copy(installed = true, inUse = true))
        assertFalse("settings.model.parakeet.delete" in ids(s))
        assertFalse("settings.model.parakeet.use" in ids(s))
        assertFalse("settings.model.parakeet.download" in ids(s))
        assertEquals(ControlTexts.BADGE_READY, label(s, "settings.model.parakeet.ready.label").text)
    }

    @Test
    fun `a model in use that is not on the phone draws download and no ready word`() {
        val s = render(small.copy(inUse = true))
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "small"), action(s, "settings.model.small.download").intent)
        assertFalse("settings.model.small.ready" in ids(s))
    }

    @Test
    fun `the spoken rating reads good one of three and better two of three and the trucks match the rating`() {
        val s = render(small, parakeet)
        assertEquals("Good, 1 of 3", label(s, "settings.model.small.label").text)
        assertEquals("Better, 2 of 3", label(s, "settings.model.parakeet.label").text)
        assertEquals(ModelRatingText.trucks(1), label(s, "settings.model.small.trucks").text)
        assertEquals(ModelRatingText.trucks(2), label(s, "settings.model.parakeet.trucks").text)
    }

    @Test
    fun `the size of each model is the existing size sentence for that size`() {
        val s = render(small, parakeet)
        assertEquals(ModelRatingText.sizeText(349), label(s, "settings.model.small.size").text)
        assertEquals(ModelRatingText.sizeText(465), label(s, "settings.model.parakeet.size").text)
    }

    @Test
    fun `the section keeps the existing speech model heading and one block per model`() {
        val s = render(small, parakeet)
        assertEquals(SetupTexts.HEADING_MODEL, label(s, SettingsIds.MODEL_LABEL).text)
        assertTrue("settings.model.small.block" in ids(s))
        assertTrue("settings.model.parakeet.block" in ids(s))
    }

    @Test
    fun `the progress line is drawn only under the model that is downloading`() {
        val s = render(small.copy(line = "a progress sentence"), parakeet)
        assertEquals("a progress sentence", label(s, "settings.model.small.line").text)
        assertFalse("settings.model.parakeet.line" in ids(s))
    }

    @Test
    fun `every control of a model row carries the prefixed action and the id of its model`() {
        val s = render(small.copy(installed = true), parakeet.copy(installed = true, inUse = true))
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DELETE, "small"), action(s, "settings.model.small.delete").intent)
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_USE, "small"), action(s, "settings.model.small.use").intent)
        assertEquals("Delete", action(s, "settings.model.small.delete").text)
        assertEquals("Use this one", action(s, "settings.model.small.use").text)
    }

    @Test
    fun `the model actions are three different strings and differ from the one download action`() {
        val actions = listOf(ModelActions.MODEL_DOWNLOAD, ModelActions.MODEL_DELETE, ModelActions.MODEL_USE, SettingsIds.DOWNLOAD_ACTION)
        assertEquals(actions.size, actions.toSet().size)
    }

    @Test
    fun `the screen offers no edit for any model intent so the host answers them`() {
        for (action in listOf(ModelActions.MODEL_DOWNLOAD, ModelActions.MODEL_DELETE, ModelActions.MODEL_USE)) {
            assertNull("model action $action", screen.editFor(ScreenIntent.Setup(action, "parakeet")))
        }
    }

    @Test
    fun `every node id in a section with rows is unique`() {
        val s = render(small.copy(installed = true, line = "a progress sentence"), parakeet.copy(installed = true, inUse = true))
        val every = ids(s)
        assertEquals("duplicate ids in $every", every.size, every.toSet().size)
    }
}
