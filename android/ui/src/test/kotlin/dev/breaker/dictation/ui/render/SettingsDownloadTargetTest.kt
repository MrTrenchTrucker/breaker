package dev.breaker.dictation.ui.render

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.ModelActions
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.screen.settings.ModelRow
import dev.breaker.dictation.ui.screen.settings.ModelRowItem
import dev.breaker.dictation.ui.screen.settings.SettingsIds
import dev.breaker.dictation.ui.screen.settings.SettingsScreen
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download control the settings host focuses and reveals, chosen from the offered model rows.
 *
 * With rows, the selected model's download control is chosen when that model is not on the phone, else the
 * first offered model that is not on the phone; nothing is chosen when every offered model is on the phone.
 * With no rows the one download id of the single button is chosen. Nothing here has been compiled or run:
 * every expectation is a hand trace.
 */
class SettingsDownloadTargetTest {

    private val small = ModelRowItem(
        id = "small", label = "Good", rating = 1, sizeMb = 349, installed = false, inUse = false, line = null,
    )

    private val parakeet = ModelRowItem(
        id = "parakeet", label = "Better", rating = 2, sizeMb = 465, installed = false, inUse = false, line = null,
    )

    @Test
    fun `rows mode with an uninstalled selected model targets its download control`() {
        assertEquals("settings.model.small.download", downloadControlIdFor(listOf(small, parakeet), "small"))
    }

    @Test
    fun `rows mode targets the selected model before an earlier model not installed`() {
        assertEquals("settings.model.parakeet.download", downloadControlIdFor(listOf(small, parakeet), "parakeet"))
    }

    @Test
    fun `rows mode with the selected model installed targets the first offered model not installed`() {
        assertEquals(
            "settings.model.small.download",
            downloadControlIdFor(listOf(small, parakeet.copy(installed = true)), "parakeet"),
        )
    }

    @Test
    fun `rows mode with every offered model installed targets no control`() {
        assertNull(downloadControlIdFor(listOf(small.copy(installed = true), parakeet.copy(installed = true)), "small"))
    }

    @Test
    fun `rows empty keeps the one download id of the single button`() {
        assertEquals(SettingsIds.DOWNLOAD_ID, downloadControlIdFor(emptyList(), null))
    }

    private val hostFlat: String = withoutComments(mainSourceOf("render/SettingsHostView.kt")).replace(Regex("""\s+"""), " ")

    private fun between(start: String, end: String): String = hostFlat.substringAfter(start, "").substringBefore(end, "")

    @Test
    fun `the focus and the reveal of the host both choose the control with the helper`() {
        val focus = between("override fun focusDownload(): Boolean {", "override fun onAttachedToWindow()")
        val reveal = between("private fun revealDownload() {", "}")
        assertTrue("render: target: focus must choose with the helper, body was: $focus",
            focus.contains("val id = downloadControlIdFor(modelRowsNow(), heldModelId()) ?: return false"))
        assertTrue("render: target: focus must look for the chosen id, body was: $focus",
            focus.contains("if (findTagged(this, id) == null) return false"))
        assertTrue("render: target: reveal must choose with the helper, body was: $reveal",
            reveal.contains("val id = downloadControlIdFor(modelRowsNow(), heldModelId()) ?: return"))
        assertTrue("render: target: reveal must look for the chosen id, body was: $reveal",
            reveal.contains("val button = findTagged(this, id) ?: return"))
    }

    private fun theme(): ThemeState = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, loaded = true)

    @Test
    fun `the control the focus goes to is an action the rendered rows draw`() {
        val rows = listOf(small, parakeet.copy(installed = true))
        val target = downloadControlIdFor(rows, "parakeet")
        val screen = SettingsScreen().render(NONDEFAULT, theme(), null, ModelRow(ready = false, line = null, rows = rows))
        val drawn = screen.nodes.flatMap { it.flatten() }
        val action = drawn.firstOrNull { it.id == target } as? Action
        assertEquals(ScreenIntent.Setup(ModelActions.MODEL_DOWNLOAD, "small"), action?.intent)
    }
}
