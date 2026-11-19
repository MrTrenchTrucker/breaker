package dev.breaker.dictation.ui

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.onboarding.FirstRunModel
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.screen.onboarding.SetupState
import dev.breaker.dictation.ui.screen.settings.ModelRow
import dev.breaker.dictation.ui.screen.settings.ModelRowItem
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The two entries need an Android context and a view, so no JVM test calls createSettingsView or createFirstRunView.
 * These tests call the screens the entries hand their models to, through the same mapping functions the entries use
 * (rowItemsOf and firstRunModelsOf). With no model the screens are today's single-model screens; with two models
 * they draw both. Hand-traced only: nothing here has been compiled or run.
 */
class SpeechModelEntryRenderTest {
    private val small = SpeechModelChoice("small", "Good", 1, 349)
    private val parakeet = SpeechModelChoice("parakeet", "Better", 2, 465)
    private val status = PlatformStatus(overlay = false, microphone = false, notifications = false, accessibility = false, sdkInt = 36)

    private fun theme(): ThemeState = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, loaded = true)

    private fun settingsScreen(rows: List<ModelRowItem>): Screen =
        SettingsScreen().render(NONDEFAULT, theme(), null, ModelRow(ready = false, line = null, rows = rows))

    private fun setupScreen(models: List<FirstRunModel>): Screen =
        SetupScreen().render(SetupState(status = status, switchedOn = false, micAskedBefore = false, models = models), null)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun byId(s: Screen, id: String): Node =
        nodesOf(s).firstOrNull { it.id == id } ?: throw AssertionError("no node with id $id in ${nodesOf(s).map { it.id }}")

    @Test
    fun `two renders built with empty rows agree that the settings screen with no model is today's one-button screen`() {
        val none = rowItemsOf(emptyList())
        assertEquals(emptyList<ModelRowItem>(), none)
        val today = SettingsScreen().render(NONDEFAULT, theme(), null, ModelRow(ready = false, line = null))
        assertEquals(today, settingsScreen(none))
        assertTrue("settings.model.download" in nodesOf(settingsScreen(none)).map { it.id })
    }

    @Test
    fun `two renders built with empty models agree that the first-run step with no model is today's one-button screen`() {
        val none = firstRunModelsOf(emptyList()) { false }
        assertEquals(emptyList<FirstRunModel>(), none)
        val today = SetupScreen().render(SetupState(status = status, switchedOn = false, micAskedBefore = false), null)
        assertEquals(today, setupScreen(none))
        assertEquals(listOf("setup.step.model.button"), nodesOf(setupScreen(none)).map { it.id }.filter { it.startsWith("setup.step.model.button") })
    }

    @Test
    fun `with two models offered the settings screen draws both, labelled Good and Better, with the ids in their intents`() {
        val s = settingsScreen(rowItemsOf(listOf(small, parakeet)))
        assertTrue((byId(s, "settings.model.small.label") as Label).text.contains("Good"))
        assertTrue((byId(s, "settings.model.parakeet.label") as Label).text.contains("Better"))
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "small"), (byId(s, "settings.model.small.download") as Action).intent)
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "parakeet"), (byId(s, "settings.model.parakeet.download") as Action).intent)
    }

    @Test
    fun `with two models offered the first-run step draws one button each, labelled Good and Better, with the ids in their intents`() {
        val s = setupScreen(firstRunModelsOf(listOf(small, parakeet)) { false })
        val first = byId(s, "setup.step.model.button") as Action
        val second = byId(s, "setup.step.model.button.parakeet") as Action
        assertTrue(first.text.contains("Good"))
        assertTrue(second.text.contains("Better"))
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "small"), first.intent)
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "parakeet"), second.intent)
    }
}
