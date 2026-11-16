package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Properties

/**
 * The server address list and its migration from the single-address file.
 *
 * An install written before the list existed holds one `server_url` and nothing
 * else; it must come back as a one-entry list, and a blank install must stay
 * blank. `server_url` stays entry 1 so every reader that never heard of the list
 * keeps the address it always had.
 */
class SettingsServerAddressMigrationTest {

    private fun properties(vararg pairs: Pair<String, String>): Properties =
        Properties().apply { pairs.forEach { (k, v) -> setProperty(k, v) } }

    @Test
    fun `an install holding only the old server_url decodes to the canonical single form`() {
        val decoded = SettingsPropertiesCodec.decode(properties("server_url" to "https://box.local"))

        assertEquals("serverUrl is the address", "https://box.local", decoded.serverUrl)
        assertEquals(
            "a single address is serverUrl alone, so the list is empty",
            emptyList<String>(),
            decoded.serverAddresses,
        )
        assertEquals("the legacy file decodes to exactly the legacy shape", AppSettings(serverUrl = "https://box.local"), decoded)
    }

    @Test
    fun `a blank install stays blank`() {
        val decoded = SettingsPropertiesCodec.decode(Properties())

        assertEquals("serverUrl", "", decoded.serverUrl)
        assertEquals("serverAddresses", emptyList<String>(), decoded.serverAddresses)
        assertEquals("serverLastGood", "", decoded.serverLastGood)
    }

    @Test
    fun `a two-entry file decodes to the two-entry list`() {
        val decoded = SettingsPropertiesCodec.decode(
            properties(
                "server_address_1" to "https://home.local",
                "server_address_2" to "https://road.local",
            ),
        )

        assertEquals(listOf("https://home.local", "https://road.local"), decoded.serverAddresses)
        assertEquals("serverUrl", "https://home.local", decoded.serverUrl)
    }

    @Test
    fun `the list is read in order and server_url is its first entry`() {
        val decoded = SettingsPropertiesCodec.decode(
            properties(
                "server_address_1" to "https://home.local",
                "server_address_2" to "https://road.local",
                "server_address_3" to "https://far.local",
            ),
        )

        assertEquals(
            "order is probe priority",
            listOf("https://home.local", "https://road.local", "https://far.local"),
            decoded.serverAddresses,
        )
        assertEquals("serverUrl", "https://home.local", decoded.serverUrl)
    }

    @Test
    fun `a written list comes back and server_url is written as its first entry`() {
        val properties = SettingsPropertiesCodec.encode(
            AppSettings(
                serverUrl = "https://home.local",
                serverAddresses = listOf("https://home.local", "https://road.local"),
                serverLastGood = "https://road.local",
            ),
        )

        assertEquals("server_url", "https://home.local", properties.getProperty("server_url"))
        assertEquals("server_address_1", "https://home.local", properties.getProperty("server_address_1"))
        assertEquals("server_address_2", "https://road.local", properties.getProperty("server_address_2"))
        assertEquals("server_last_good", "https://road.local", properties.getProperty("server_last_good"))
    }

    @Test
    fun `a caller that only sets serverUrl does not lose the address`() {
        val properties = SettingsPropertiesCodec.encode(AppSettings(serverUrl = "https://box.local"))

        assertEquals("server_url", "https://box.local", properties.getProperty("server_url"))
        assertEquals("server_address_1", "https://box.local", properties.getProperty("server_address_1"))
    }

    @Test
    fun `a trailing blank entry is not stored as an entry`() {
        val decoded = SettingsPropertiesCodec.decode(
            properties(
                "server_address_1" to "https://home.local",
                "server_address_2" to "",
            ),
        )

        assertEquals("one surviving entry is the canonical single form", emptyList<String>(), decoded.serverAddresses)
        assertEquals("the address is still serverUrl", "https://home.local", decoded.serverUrl)
    }

    @Test
    fun `a blank entry 1 is dropped so entry 2 becomes the list head`() {
        // D2: a blank entry is "not set". Keeping it would give a list whose
        // entry 1 is empty while a real address sits at entry 2.
        val decoded = SettingsPropertiesCodec.decode(
            properties(
                "server_address_1" to "",
                "server_address_2" to "https://road.local",
            ),
        )

        assertEquals("one surviving entry is the canonical single form", emptyList<String>(), decoded.serverAddresses)
        assertEquals("serverUrl", "https://road.local", decoded.serverUrl)
    }

    @Test
    fun `a blank entry in the middle is dropped keeping the order of the others`() {
        val decoded = SettingsPropertiesCodec.decode(
            properties(
                "server_address_1" to "https://home.local",
                "server_address_2" to "",
                "server_address_3" to "https://far.local",
            ),
        )

        assertEquals(listOf("https://home.local", "https://far.local"), decoded.serverAddresses)
        assertEquals("serverUrl", "https://home.local", decoded.serverUrl)
    }

    @Test
    fun `a consistent copy round-trips through encode and decode`() {
        val loaded = SettingsPropertiesCodec.decode(
            properties(
                "server_address_1" to "https://home.local",
                "server_address_2" to "https://road.local",
            ),
        )
        val edited = loaded.copy(
            serverUrl = "https://far.local",
            serverAddresses = listOf("https://far.local", "https://road.local"),
        )

        val written = SettingsPropertiesCodec.encode(edited)
        val back = SettingsPropertiesCodec.decode(written)

        assertEquals(edited.serverAddresses, back.serverAddresses)
        assertEquals(edited.serverUrl, back.serverUrl)
        assertEquals("server_url follows the new list head", "https://far.local", written.getProperty("server_url"))
    }
}
