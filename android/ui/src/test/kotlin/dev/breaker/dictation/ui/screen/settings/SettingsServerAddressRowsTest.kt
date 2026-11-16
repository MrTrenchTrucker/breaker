package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.testing.SINGLE
import dev.breaker.dictation.ui.testing.THREE_ENTRIES
import dev.breaker.dictation.ui.testing.TWO_ENTRIES
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nodes of [s], flattened. */
private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

/** The server address fields, in drawn order. */
private fun fieldsOf(s: Screen): List<ValueField> = nodesOf(s).filterIsInstance<ValueField>()
    .filter { it.id.startsWith(SettingsIds.SERVER_FIELD) }

/**
 * The server address rows: the stored list in order, the draft rows the add and
 * remove controls make, the four unavailable controls, and the save that writes
 * the list and its first entry together.
 *
 * A row the person adds is a draft of the screen: it is drawn and it is not
 * stored, so a draft that is never filled leaves the stored settings alone.
 */
class SettingsServerAddressRowsTest {
    private val screen = SettingsScreen()

    private fun themeOf() = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, true)

    /** The server row controls: the add control and each row's remove and move controls. */
    private fun actionsOf(s: Screen): List<Action> = nodesOf(s).filterIsInstance<Action>()
        .filter { it.id.startsWith(SettingsIds.SERVER_ROW) }

    private fun action(s: Screen, id: String): Action =
        actionsOf(s).firstOrNull { it.id == id } ?: throw AssertionError("no action with id $id")

    /** The notice on the screen, or null when none is shown. */
    private fun Rendered.noticeText(): String? =
        screen.nodes.flatMap { it.flatten() }.firstOrNull { it.id == "settings.notice" }
            ?.let { (it as Label).text }

    /** A handler over a store holding [held], with the phone in light mode. */
    private class Fixture(held: AppSettings) {
        val store = FakeSettingsStore(held)
        val handler = SettingsIntentHandler(store, ThemeController(store, systemIsDark = false), SettingsScreen())

        /** The rows the handler draws now. */
        fun fields(): List<ValueField> = fieldsOf(handler.current().screen)
    }

    @Test
    fun `the stored rows render in stored order, entry 1 first, each with its own key`() {
        val held = THREE_ENTRIES
        val s = screen.render(held, themeOf(), null)
        assertEquals(
            listOf("settings.server.field", "settings.server.field.2", "settings.server.field.3"),
            fieldsOf(s).map { it.id },
        )
        assertEquals(
            listOf("https://a.example", "https://b.example", "https://c.example"),
            fieldsOf(s).map { it.text },
        )
        assertEquals(
            listOf("server_url", "server_address_2", "server_address_3"),
            fieldsOf(s).map { it.entryKey },
        )
        assertTrue("a stored row is not muted", fieldsOf(s).none { it.muted })
    }

    @Test
    fun `the blank install draws the one row of the blank install`() {
        val s = screen.render(SINGLE.copy(serverUrl = ""), themeOf(), null)
        assertEquals(listOf("settings.server.field"), fieldsOf(s).map { it.id })
        assertEquals("(not set)", fieldsOf(s).single().text)
        assertTrue("the blank row is muted", fieldsOf(s).single().muted)
        assertEquals("server_url", fieldsOf(s).single().entryKey)
    }

    @Test
    fun `add appends a draft row, caps at three and stores nothing`() {
        val f = Fixture(SINGLE)
        assertEquals(1, f.fields().size)
        f.handler.handle(ScreenIntent.AddServerAddress(1))
        assertEquals(2, f.fields().size)
        assertEquals("(not set)", f.fields()[1].text)
        assertTrue("a draft row is muted", f.fields()[1].muted)
        f.handler.handle(ScreenIntent.AddServerAddress(2))
        assertEquals(3, f.fields().size)
        f.handler.handle(ScreenIntent.AddServerAddress(3))
        assertEquals("three rows is the most the screen offers", 3, f.fields().size)
        assertEquals("a draft row is not stored", 0, f.store.saveAttempts)
        assertEquals(SINGLE, f.store.current)
    }

    @Test
    fun `remove takes a row away, keeps one row at the least and stores nothing`() {
        val held = TWO_ENTRIES
        val f = Fixture(held)
        assertEquals(2, f.fields().size)
        f.handler.handle(ScreenIntent.RemoveServerAddress(1))
        assertEquals(1, f.fields().size)
        assertEquals("https://a.example", f.fields().single().text)
        f.handler.handle(ScreenIntent.RemoveServerAddress(0))
        assertEquals("one row is the least the screen keeps", 1, f.fields().size)
        assertEquals("a removed row is not stored", 0, f.store.saveAttempts)
        assertEquals(held, f.store.current)
    }

    @Test
    fun `the four controls are disabled exactly where the screen cannot act`() {
        val one = Fixture(SINGLE)
        val oneRow = one.handler.current().screen
        assertTrue("add is available while fewer than three rows show", action(oneRow, SERVER_ROW_ADD_ID).enabled)
        assertFalse("remove is unavailable while one row remains", action(oneRow, "settings.server.remove.1").enabled)
        assertFalse("move up is unavailable on row 1", action(oneRow, "settings.server.up.1").enabled)
        assertFalse("move down is unavailable on the last row", action(oneRow, "settings.server.down.1").enabled)

        val three = Fixture(THREE_ENTRIES)
        val threeRows = three.handler.current().screen
        assertFalse("add is unavailable at three rows", action(threeRows, SERVER_ROW_ADD_ID).enabled)
        assertTrue(action(threeRows, "settings.server.remove.1").enabled)
        assertTrue(action(threeRows, "settings.server.remove.3").enabled)
        assertFalse("move up is unavailable on row 1", action(threeRows, "settings.server.up.1").enabled)
        assertTrue(action(threeRows, "settings.server.up.2").enabled)
        assertTrue(action(threeRows, "settings.server.down.2").enabled)
        assertFalse("move down is unavailable on the last row", action(threeRows, "settings.server.down.3").enabled)
    }

    @Test
    fun `each row control carries the row action and the words held for it`() {
        val s = screen.render(SINGLE, themeOf(), null)
        assertEquals(ScreenIntent.AddServerAddress(1), action(s, SERVER_ROW_ADD_ID).intent)
        assertEquals(ScreenIntent.RemoveServerAddress(0), action(s, "settings.server.remove.1").intent)
        assertEquals(ScreenIntent.MoveServerAddressUp(0), action(s, "settings.server.up.1").intent)
        assertEquals(ScreenIntent.MoveServerAddressDown(0), action(s, "settings.server.down.1").intent)
        assertEquals(SettingsTexts.SERVER_ROW_ADD, action(s, SERVER_ROW_ADD_ID).text)
        assertEquals(SettingsTexts.SERVER_ROW_REMOVE, action(s, "settings.server.remove.1").text)
        assertEquals(SettingsTexts.SERVER_ROW_MOVE_UP, action(s, "settings.server.up.1").text)
        assertEquals(SettingsTexts.SERVER_ROW_MOVE_DOWN, action(s, "settings.server.down.1").text)
    }

    @Test
    fun `move up and down reorder the stored rows and keep the list order as probe priority`() {
        val held = THREE_ENTRIES
        val f = Fixture(held)
        f.handler.handle(ScreenIntent.MoveServerAddressDown(0))
        assertEquals(
            listOf("https://b.example", "https://a.example", "https://c.example"),
            f.fields().map { it.text },
        )
        f.handler.handle(ScreenIntent.MoveServerAddressUp(1))
        assertEquals(
            listOf("https://a.example", "https://b.example", "https://c.example"),
            f.fields().map { it.text },
        )
        assertEquals("each move is stored once", 2, f.store.saveAttempts)
        assertEquals(
            listOf("https://a.example", "https://b.example", "https://c.example"),
            f.store.current.serverAddresses,
        )
        assertEquals("https://a.example", f.store.current.serverUrl)
    }

    @Test
    fun `a row the rule refuses is not saved and the stored rows stay`() {
        val held = TWO_ENTRIES
        val f = Fixture(held)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_address_2", "ftp://box.example"))
        assertEquals("a refused row saves nothing", 0, f.store.saveAttempts)
        assertEquals("That server address cannot be used. Nothing was changed.", after.noticeText())
        assertEquals(listOf("https://a.example", "https://b.example"), f.fields().map { it.text })
        assertEquals(held, f.store.current)
    }

    @Test
    fun `a list the rule refuses whole is not saved`() {
        val held = TWO_ENTRIES
        val f = Fixture(held)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_address_2", "https://a.example"))
        assertEquals("a duplicate entry refuses the whole list", 0, f.store.saveAttempts)
        assertEquals("That server address cannot be used. Nothing was changed.", after.noticeText())
        assertEquals(held, f.store.current)
    }

    @Test
    fun `a blank row in the middle is removed before the rule runs, so the list saves two entries in order`() {
        val f = Fixture(THREE_ENTRIES)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_address_2", ""))
        assertEquals("clearing a row saves once", 1, f.store.saveAttempts)
        assertEquals(
            "the blank is not stored and the survivors keep their order",
            listOf("https://a.example", "https://c.example"),
            f.store.current.serverAddresses,
        )
        assertEquals("https://a.example", f.store.current.serverUrl)
        assertEquals("a refused edit would have noticed; this one did not", null, after.noticeText())
    }

    @Test
    fun `clearing row 1 of two promotes the second to entry 1 instead of refusing`() {
        val f = Fixture(TWO_ENTRIES)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url", ""))
        assertEquals(1, f.store.saveAttempts)
        assertEquals(
            "the second entry is now entry 1, and a one-entry list is serverUrl alone",
            emptyList<String>(),
            f.store.current.serverAddresses,
        )
        assertEquals("https://b.example", f.store.current.serverUrl)
        assertEquals(null, after.noticeText())
    }

    @Test
    fun `clearing the only stored row clears the address`() {
        val f = Fixture(SINGLE)
        val after = f.handler.handle(ScreenIntent.SetSetting("server_url", ""))
        assertEquals(1, f.store.saveAttempts)
        assertEquals("", f.store.current.serverUrl)
        assertEquals(emptyList<String>(), f.store.current.serverAddresses)
        assertEquals(null, after.noticeText())
    }

    @Test
    fun `a typed row beyond the stored count lands at the end of the list`() {
        val f = Fixture(SINGLE)
        f.handler.handle(ScreenIntent.SetSetting("server_address_3", "https://c.example"))
        assertEquals(
            listOf("https://srv.example.invalid:8443", "https://c.example"),
            f.store.current.serverAddresses,
        )
        assertEquals("https://srv.example.invalid:8443", f.store.current.serverUrl)
    }

    @Test
    fun `a plain http row carries its own plain http line`() {
        val held = TWO_ENTRIES.copy(serverAddresses = listOf("https://a.example", "http://b.example"))
        val s = screen.render(held, themeOf(), null)
        val lines = nodesOf(s).filter { it.id.startsWith(SettingsIds.PLAIN_HTTP_LINE) }
        assertEquals(listOf("settings.server.plainHttp.2"), lines.map { it.id })
        assertEquals(SettingsTexts.PLAIN_HTTP_HELP, (lines.single() as Label).text)
    }

    @Test
    fun `the save writes the list and its first entry together`() {
        val f = Fixture(SINGLE)
        f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertEquals(
            SINGLE.copy(serverUrl = "https://box.example"),
            f.store.current,
        )
        assertEquals(1, f.store.saveAttempts)
    }

    @Test
    fun `editing the only stored address yields the canonical single form, not a one-entry list`() {
        val f = Fixture(SINGLE)
        f.handler.handle(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertEquals(
            "the list stays empty while there is only one address",
            emptyList<String>(),
            f.store.current.serverAddresses,
        )
        assertEquals("https://box.example", f.store.current.serverUrl)
        assertEquals(
            "and the stored settings equal the same address held the legacy way",
            SINGLE.copy(serverUrl = "https://box.example"),
            f.store.current,
        )
    }

    @Test
    fun `a draft row that is filled becomes a stored row`() {
        val f = Fixture(SINGLE)
        f.handler.handle(ScreenIntent.AddServerAddress(1))
        f.handler.handle(ScreenIntent.SetSetting("server_address_2", "https://b.example"))
        assertEquals(
            listOf("https://srv.example.invalid:8443", "https://b.example"),
            f.store.current.serverAddresses,
        )
        assertEquals("https://srv.example.invalid:8443", f.store.current.serverUrl)
        assertEquals("only the fill is stored, not the add", 1, f.store.saveAttempts)
    }
}
