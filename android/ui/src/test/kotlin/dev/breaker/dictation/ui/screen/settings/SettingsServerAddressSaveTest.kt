package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FAILURE_MARKER
import dev.breaker.dictation.ui.testing.FAILURE_URL
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save path of the server address, through the same handler the settings screen uses.
 *
 * The stored address in every fixture is the literal "https://srv.example.invalid:8443", which is
 * the address of the shared fixture. Each refusal must leave it in place and show the fixed notice.
 */
class SettingsServerAddressSaveTest {
    private class Fixture(held: dev.breaker.dictation.core.model.AppSettings) {
        val store = FakeSettingsStore(held)
        val handler = SettingsIntentHandler(
            store,
            ThemeController(store, systemIsDark = false),
            SettingsScreen(),
        )
    }

    private val refusedNotice = "That server address cannot be used. Nothing was changed."
    private val generalNotice = "That change was not accepted. Nothing was changed."
    private val saveNotice = "Your settings could not be saved. Nothing was changed."
    private val storedText = "https://srv.example.invalid:8443"

    private val refusedTexts = listOf(
        "box.example",
        "ftp://box.example",
        "https://:8080",
        "https://box.example:0",
        "https://box.example:65536",
        "https://u@box.example",
        "https://box.example?x",
        "https://box.example/a//b",
        "https://bo x.example",
        "https://box.example#x",
    )

    /** The text of the fixed notice on the screen, or null when none is shown. */
    private fun Rendered.noticeText(): String? =
        screen.nodes.flatMap { it.flatten() }
            .firstOrNull { it.id == "settings.notice" }?.let { (it as Label).text }

    /** Every text the screen draws: labels, actions and the value fields. */
    private fun Rendered.shownTexts(): List<String> =
        screen.nodes.flatMap { it.flatten() }.mapNotNull { node ->
            when (node) {
                is Label -> node.text
                is Action -> node.text
                is ValueField -> node.text
                else -> null
            }
        }

    private fun Rendered.serverField(): ValueField? =
        screen.nodes.flatMap { it.flatten() }.filterIsInstance<ValueField>()
            .firstOrNull { it.id == "settings.server.field" }

    @Test
    fun `a good address is stored and read back through the store`() {
        val f = Fixture(NONDEFAULT)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertEquals("https://box.example", f.store.current.serverUrl)
        assertEquals("https://box.example", f.store.load().serverUrl)
        assertEquals(1, f.store.saveAttempts)
        assertNull(after.noticeText())
        assertEquals("https://box.example", after.serverField()?.text)
    }

    @Test
    fun `each refused address saves nothing and keeps the stored text with the fixed notice`() {
        for (typed in refusedTexts) {
            val f = Fixture(NONDEFAULT)
            val loadsBefore = f.store.loadAttempts
            val after = f.handler.handle(ScreenIntent.SetSetting("server_url", typed))
            assertEquals("saves for $typed", 0, f.store.saveAttempts)
            assertEquals("loads for $typed", loadsBefore, f.store.loadAttempts)
            assertTrue("no save reached for $typed", f.store.saves.isEmpty())
            assertEquals("stored after $typed", NONDEFAULT, f.store.current)
            assertEquals("notice for $typed", refusedNotice, after.noticeText())
            assertEquals("box after $typed", storedText, after.serverField()?.text)
            assertTrue("typed text on screen for $typed", after.shownTexts().none { it.contains(typed) })
        }
    }

    @Test
    fun `a blank box clears the address and the row shows the words for not set`() {
        val f = Fixture(NONDEFAULT)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url", "   "))
        assertEquals("", f.store.current.serverUrl)
        assertEquals(1, f.store.saveAttempts)
        val field = after.serverField()
        assertEquals("(not set)", field?.text)
        assertTrue("the row is muted", field?.muted == true)
        assertNull(after.noticeText())
    }

    @Test
    fun `the same address again is not saved a second time`() {
        val f = Fixture(NONDEFAULT)
        f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertEquals(1, f.store.saveAttempts)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertEquals(1, f.store.saveAttempts)
        assertNull(after.noticeText())
        assertEquals("https://box.example", f.store.current.serverUrl)
    }

    @Test
    fun `a failing save says so and keeps the stored text with no part of the failure shown`() {
        val f = Fixture(NONDEFAULT)
        f.store.failSave = true
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertEquals(1, f.store.saveAttempts)
        assertEquals(saveNotice, after.noticeText())
        assertEquals(NONDEFAULT, f.store.current)
        assertTrue("a save reached storage", f.store.saves.isEmpty())
        assertTrue(
            "failure text on screen",
            after.shownTexts().none { it.contains(FAILURE_MARKER) || it.contains(FAILURE_URL) },
        )
        assertEquals(storedText, after.serverField()?.text)
    }

    @Test
    fun `a good save changes the address and no other setting`() {
        val f = Fixture(NONDEFAULT)
        f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertEquals(NONDEFAULT.copy(serverUrl = "https://box.example"), f.store.current)
    }

    @Test
    fun `a good address after a refusal clears the notice`() {
        val f = Fixture(NONDEFAULT)
        val refused = f.handler.handle(ScreenIntent.SetSetting("server_url", "box.example"))
        assertEquals(refusedNotice, refused.noticeText())
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertNull(after.noticeText())
        assertEquals("https://box.example", f.store.current.serverUrl)
    }

    @Test
    fun `a key that only starts with the address key is refused with the general notice`() {
        val f = Fixture(NONDEFAULT)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url_x", "https://box.example"))
        assertEquals(generalNotice, after.noticeText())
        assertEquals(0, f.store.saveAttempts)
        assertEquals(NONDEFAULT, f.store.current)
    }

    @Test
    fun `the same text under the address key is saved and is the control for the key test`() {
        val f = Fixture(NONDEFAULT)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertEquals(1, f.store.saveAttempts)
        assertNull(after.noticeText())
    }
}
