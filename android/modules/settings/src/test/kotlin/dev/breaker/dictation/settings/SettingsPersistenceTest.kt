package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * Durability: a value written by one store instance comes back from a *different*
 * instance over the same file, and the saved file carries exactly the nine keys.
 *
 * The fixture — the temporary folder, the store, the non-default settings value
 * and the raw read-back — is in [SettingsFileStoreTestBase].
 */
class SettingsPersistenceTest : SettingsFileStoreTestBase() {

    @Test
    fun `every non-default value survives a new store instance over the same file`() {
        val file = newFile()
        val saved = validSettings()

        store(file).save(saved)
        val reloaded = store(file).load()

        // Each key asserted by name, so a failure names the key that broke.
        assertEquals("mode", SttMode.SERVER, reloaded.mode)
        assertEquals("model_size", "medium", reloaded.modelSize)
        assertEquals("server_url", "https://box.local", reloaded.serverUrl)
        assertEquals("wake_gesture_enabled", false, reloaded.wakeGestureEnabled)
        assertEquals("language", "de", reloaded.language)
        assertEquals("preload_model", false, reloaded.preloadModel)
        assertEquals("formatting_enabled", false, reloaded.formattingEnabled)
        assertEquals("theme_mode", ThemeMode.DARK, reloaded.themeMode)
        assertEquals("tile_position x", 0.25f, reloaded.tilePosition.x, 0f)
        assertEquals("tile_position y", 0.75f, reloaded.tilePosition.y, 0f)
    }

    @Test
    fun `saving twice keeps both writes`() {
        val file = newFile()
        val store = store(file)

        store.save(validSettings())
        store.save(validSettings().copy(mode = SttMode.LOCAL, language = "fr"))

        val reloaded = store(file).load()
        assertEquals(SttMode.LOCAL, reloaded.mode)
        assertEquals("fr", reloaded.language)
    }

    @Test
    fun `enums are stored by name rather than by ordinal`() {
        val file = newFile()
        store(file).save(validSettings())

        val raw = rawPropertiesOf(file)

        // An ordinal write ("1") survives a same-build round trip but breaks
        // every reordering of the enum, and reads back as garbage after one.
        assertEquals("theme_mode", ThemeMode.DARK.name, raw.getProperty(KEY_THEME_MODE))
        assertEquals("mode", SttMode.SERVER.name, raw.getProperty(KEY_MODE))
        assertEquals("DARK", raw.getProperty(KEY_THEME_MODE))
        assertEquals("SERVER", raw.getProperty(KEY_MODE))
    }

    @Test
    fun `a saved file carries exactly the nine keys and no credential key`() {
        val file = newFile()
        store(file).save(validSettings())

        val keys = rawPropertiesOf(file).stringPropertyNames()

        assertEquals("the saved key set", THE_NINE_KEYS, keys)
        assertTrue(
            "a credential-like key was written: $keys",
            credentialLikeKeysIn(keys).isEmpty(),
        )
    }

    @Test
    fun `the nine keys are all actually written, none silently skipped`() {
        val file = newFile()
        store(file).save(validSettings())

        val raw = rawPropertiesOf(file)

        for (key in THE_NINE_KEYS) {
            assertNotNull("no value was written under $key", raw.getProperty(key))
        }
    }

    @Test
    fun `a saved file that once held a credential key is not the store's doing`() {
        // Guards the other direction: a file carrying an extra key must not make
        // the store adopt it as a setting on load.
        val props = Properties()
        THE_NINE_KEYS.forEach { key ->
            props.setProperty(
                key,
                when (key) {
                    KEY_MODE -> SttMode.SERVER.name
                    KEY_THEME_MODE -> ThemeMode.DARK.name
                    KEY_TILE_POSITION -> "0.25,0.75"
                    KEY_WAKE_GESTURE, KEY_PRELOAD_MODEL, KEY_FORMATTING -> "false"
                    KEY_MODEL_SIZE -> "medium"
                    KEY_SERVER_URL -> "https://box.local"
                    else -> "de"
                },
            )
        }
        props.setProperty("api_key", "PLANTED")
        val file = newFile()
        file.outputStream().use { props.store(it, "test") }

        val loaded = store(file).load()

        // The store must not surface an unknown key as a setting, and must not
        // fail on one either. apiKeyRef is the only place a credential could
        // surface, and it comes from the Keystore port, never the file.
        assertNull("an unknown file key became the credential reference", loaded.apiKeyRef)
        assertEquals(SttMode.SERVER, loaded.mode)
        assertEquals("medium", loaded.modelSize)
    }
}
