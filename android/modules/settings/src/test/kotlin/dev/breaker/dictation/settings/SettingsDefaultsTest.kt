package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A file that is absent, or present and empty, loads core's own defaults for
 * every file-backed key — while the credential reference still comes from the
 * Keystore port, because the file is not where it lives.
 *
 * The port is seeded on purpose in each test: an empty fake would make the
 * reference trivially null, and the test could not tell a load that asks the
 * port from one that hardcodes a null.
 */
class SettingsDefaultsTest : SettingsFileStoreTestBase() {

    /**
     * Asserts all nine file-backed keys carry core's own defaults, by name, so a
     * failure names the key that broke.
     */
    private fun assertTheNineDefaults(loaded: AppSettings) {
        assertEquals("mode", AppSettings().mode, loaded.mode)
        assertEquals("model_size", AppSettings().modelSize, loaded.modelSize)
        assertEquals("server_url", AppSettings().serverUrl, loaded.serverUrl)
        assertEquals("wake_gesture_enabled", AppSettings().wakeGestureEnabled, loaded.wakeGestureEnabled)
        assertEquals("tile_position", AppSettings().tilePosition, loaded.tilePosition)
        assertEquals("language", AppSettings().language, loaded.language)
        assertEquals("preload_model", AppSettings().preloadModel, loaded.preloadModel)
        assertEquals("formatting_enabled", AppSettings().formattingEnabled, loaded.formattingEnabled)
        assertEquals("theme_mode", AppSettings().themeMode, loaded.themeMode)
    }

    @Test
    fun `a missing file loads the core defaults`() {
        // The port is seeded on purpose. An empty fake would make the reference
        // trivially null and this test could not tell a load that asks the port
        // from one that hardcodes a null, so a reference is put in the port and
        // required to come back.
        val keystore = FakeKeystore().apply { setActiveRef("REFMISSING01") }

        val reloaded = store(newFile("absent.properties"), keystore).load()

        assertEquals("apiKeyRef", "REFMISSING01", reloaded.apiKeyRef)
        assertTheNineDefaults(reloaded)
    }

    @Test
    fun `an empty file loads the core defaults`() {
        val file = newFile()
        file.writeText("")
        val keystore = FakeKeystore().apply { setActiveRef("REFEMPTY001") }

        val reloaded = store(file, keystore).load()

        assertEquals("apiKeyRef", "REFEMPTY001", reloaded.apiKeyRef)
        assertTheNineDefaults(reloaded)
    }

    @Test
    fun `a missing file still reports the reference the port remembers`() {
        // Seeded on the port directly, with no save: the file never existed, so
        // nothing in it can account for the reference. It has to come from the
        // port, and the nine file-backed keys still have to be core's defaults —
        // both halves of one rule, asserted here together.
        val keystore = FakeKeystore().apply { setActiveRef("SOMEREF") }
        val file = newFile("never-written.properties")
        assertFalse("the test is meaningless unless the file is absent", file.exists())

        val reloaded = store(file, keystore).load()

        assertEquals("apiKeyRef", "SOMEREF", reloaded.apiKeyRef)
        assertTheNineDefaults(reloaded)
    }

    @Test
    fun `an empty file still reports the reference the port remembers`() {
        val keystore = FakeKeystore().apply { setActiveRef("SOMEREF") }
        val file = newFile()
        file.writeText("")

        val reloaded = store(file, keystore).load()

        assertEquals("apiKeyRef", "SOMEREF", reloaded.apiKeyRef)
        assertTheNineDefaults(reloaded)
    }
}
