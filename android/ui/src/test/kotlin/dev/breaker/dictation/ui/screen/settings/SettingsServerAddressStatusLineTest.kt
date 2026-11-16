package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.SINGLE
import dev.breaker.dictation.ui.testing.TWO_ENTRIES
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The one status line under the server address rows.
 *
 * Every fixture here is a hand-written object over values - a real [AppSettings] and a plain
 * lambda returning a status. Nothing in this file can open a socket, read a cache or dial a
 * probe: the screen is handed the answer, and the answer comes from a value on this page.
 */

/** The nodes of [s], flattened. */
private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

/** The status line of [s], or null when the screen drew none. */
private fun statusLineOf(s: Screen): Label? =
    nodesOf(s).filterIsInstance<Label>().firstOrNull { it.id == SERVER_ADDRESS_STATUS_LINE_ID }

/**
 * A genuinely blank install: no stored address row and no single-address fallback.
 *
 * The other fixtures are not blank. [SINGLE] carries one address in `serverUrl` and the settings
 * reader falls back to it when the row list is empty, so `serverAddressRows(SINGLE)` holds one
 * non-blank entry and the status line IS drawn over it. Only this one is truly empty.
 */
private val BLANK: AppSettings = SINGLE.copy(serverUrl = "", serverAddresses = emptyList())

class SettingsServerAddressStatusLineTest {
    private val screen = SettingsScreen()

    private fun themeOf() = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, true)

    /** A handler over a store holding [held], with the phone in light mode. */
    private class Fixture(held: AppSettings) {
        val store = FakeSettingsStore(held)
        val handler = SettingsIntentHandler(store, ThemeController(store, systemIsDark = false), SettingsScreen())
    }

    @Test
    fun `the checking line is drawn when nothing has been measured yet`() {
        assertEquals(
            SettingsTexts.SERVER_STATUS_CHECKING,
            serverAddressStatusLine(ServerAddressStatus.Checking, listOf("https://a.example"))?.text,
        )
        assertTrue(
            "the checking line must carry the declared node id",
            serverAddressStatusLine(ServerAddressStatus.Checking, listOf("https://a.example"))
                ?.id == SERVER_ADDRESS_STATUS_LINE_ID,
        )
    }

    @Test
    fun `the in use line names the winning address`() {
        assertEquals(
            "Using https://b.example.",
            serverAddressStatusLine(ServerAddressStatus.InUse("https://b.example"), listOf("https://a.example", "https://b.example"))?.text,
        )
    }

    @Test
    fun `the none answered line is drawn when every stored entry failed`() {
        assertEquals(
            SettingsTexts.SERVER_STATUS_NONE_ANSWERED,
            serverAddressStatusLine(ServerAddressStatus.NoneAnswered, listOf("https://a.example", "https://b.example"))?.text,
        )
    }

    @Test
    fun `no line is drawn when no address is set`() {
        // Whatever the status claims, there is nothing to report over an empty list.
        assertNull(serverAddressStatusLine(ServerAddressStatus.Checking, emptyList()))
        assertNull(serverAddressStatusLine(ServerAddressStatus.InUse("https://a.example"), emptyList()))
        assertNull(serverAddressStatusLine(ServerAddressStatus.NoneAnswered, listOf("   ")))
        // ... and the screen leaves it out too, deciding from the list it already holds.
        assertNull(statusLineOf(screen.render(BLANK, themeOf(), null, serverAddressStatus = ServerAddressStatus.InUse("https://stale.example"))))
    }

    @Test
    fun `no line is drawn when the host wires no provider`() {
        val rendered = screen.render(TWO_ENTRIES, themeOf(), null)
        assertNull("an unwired provider must draw no line at all", statusLineOf(rendered))

        val fixture = Fixture(TWO_ENTRIES)
        fixture.handler.showingServerAddressStatus(null)
        assertNull(statusLineOf(fixture.handler.current().screen))
    }

    @Test
    fun `the line follows what the app hands in at each drawing`() {
        val fixture = Fixture(TWO_ENTRIES)

        // Handed the first winner, the screen says so.
        fixture.handler.showingServerAddressStatus(ServerAddressStatus.InUse("https://a.example"))
        assertEquals(
            "Using https://a.example.",
            statusLineOf(fixture.handler.current().screen)?.text,
        )

        // Handed a DIFFERENT answer at the next drawing, it says that instead: nothing from the
        // earlier drawing is carried over, because the screen keeps no state of its own.
        fixture.handler.showingServerAddressStatus(ServerAddressStatus.InUse("https://b.example"))
        assertEquals(
            "Using https://b.example.",
            statusLineOf(fixture.handler.current().screen)?.text,
        )

        // Handed nothing at all, the line is simply gone.
        fixture.handler.showingServerAddressStatus(null)
        assertNull(statusLineOf(fixture.handler.current().screen))

        // And a blank install draws no line whatever it is handed: there is nothing to report over.
        val blank = Fixture(BLANK)
        blank.handler.showingServerAddressStatus(ServerAddressStatus.InUse("https://stale.example"))
        assertNull(statusLineOf(blank.handler.current().screen))
    }

    @Test
    fun `two reads of the status in the same state agree`() {
        val fixture = Fixture(TWO_ENTRIES)
        fixture.handler.showingServerAddressStatus(ServerAddressStatus.InUse("https://a.example"))

        val first = statusLineOf(fixture.handler.current().screen)
        val second = statusLineOf(fixture.handler.current().screen)
        assertEquals("the two readings must name the same line", first?.id, second?.id)
        assertEquals("the two readings must say the same words", first?.text, second?.text)

        // The same is true on the pure mapping: one value, one answer.
        val a = serverAddressStatusLine(ServerAddressStatus.InUse("https://a.example"), listOf("https://a.example"))
        val b = serverAddressStatusLine(ServerAddressStatus.InUse("https://a.example"), listOf("https://a.example"))
        assertEquals(a?.text, b?.text)
    }

    @Test
    fun `the node id is the id the new file declares`() {
        assertEquals("settings.server.status", SERVER_ADDRESS_STATUS_LINE_ID)
        // The line is a child of the block it sits in, and it is the only node carrying that id.
        val fixture = Fixture(TWO_ENTRIES)
        fixture.handler.showingServerAddressStatus(ServerAddressStatus.NoneAnswered)
        assertEquals(
            listOf(SERVER_ADDRESS_STATUS_LINE_ID),
            nodesOf(fixture.handler.current().screen).filterIsInstance<Label>()
                .filter { it.id.endsWith(".status") }
                .map { it.id },
        )
    }
}
