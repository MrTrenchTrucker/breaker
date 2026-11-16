package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppSettings.serverUrl] is entry 1 of [AppSettings.serverAddresses]: the two
 * fields are one address in two places, so a construction that makes them
 * disagree is refused loudly instead of being silently resolved by the codec.
 *
 * Without the invariant, the only writer today — the settings screen's
 * `copy(serverUrl = …)` — would keep the old list while changing `serverUrl`,
 * and the codec, which prefers the list, would write the old address back.
 */
class AppSettingsServerAddressTest {

    /** A loaded one-entry install: both fields name the same address. */
    private val loaded = AppSettings(
        serverUrl = "https://home.example",
        serverAddresses = listOf("https://home.example"),
    )

    @Test
    fun `a copy that changes only serverUrl is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            loaded.copy(serverUrl = "https://road.example")
        }
    }

    @Test
    fun `a copy that sets both fields is accepted`() {
        val edited = loaded.copy(
            serverUrl = "https://road.example",
            serverAddresses = listOf("https://road.example"),
        )

        assertEquals("https://road.example", edited.serverUrl)
        assertEquals(listOf("https://road.example"), edited.serverAddresses)
    }

    @Test
    fun `an empty list with a stored serverUrl stays legal`() {
        // The legacy single-address shape: decode migrates it to a one-entry
        // list, and the codec's entriesOf falls back to serverUrl on write.
        val legacy = AppSettings(serverUrl = "https://home.example")

        assertTrue(legacy.serverAddresses.isEmpty())
        assertEquals("https://home.example", legacy.serverUrl)
        assertEquals(listOf("https://home.example"), legacy.serverAddressList())
    }

    @Test
    fun `a blank install has no addresses and no list`() {
        val blank = AppSettings()

        assertEquals("", blank.serverUrl)
        assertTrue(blank.serverAddresses.isEmpty())
        assertTrue(blank.serverAddressList().isEmpty())
    }

    @Test
    fun `serverAddressList is the list, or the stored address when the list is empty`() {
        assertEquals(
            listOf("https://a.example", "https://b.example"),
            AppSettings(
                serverUrl = "https://a.example",
                serverAddresses = listOf("https://a.example", "https://b.example"),
            ).serverAddressList(),
        )
        assertEquals(listOf("https://a.example"), AppSettings(serverUrl = "https://a.example").serverAddressList())
    }
}
