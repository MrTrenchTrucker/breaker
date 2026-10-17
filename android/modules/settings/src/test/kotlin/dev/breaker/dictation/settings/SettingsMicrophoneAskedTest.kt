package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.SttMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The microphone prompt flag is stored with the other settings: it survives a
 * new store instance, it is written under its own key, and a damaged value or
 * an old file costs nothing else.
 *
 * The fixture is [SettingsFileStoreTestBase]. The flag is set with `copy` in
 * each test that needs it, because [validSettings] leaves it at its default.
 */
class SettingsMicrophoneAskedTest : SettingsFileStoreTestBase() {

    /** The nine settings keys as text, at the values [validSettings] carries. */
    private val nineKeyLines = listOf(
        "mode=SERVER",
        "model_size=medium",
        "server_url=https://box.local",
        "wake_gesture_enabled=false",
        "tile_position=0.25,0.75",
        "language=de",
        "preload_model=false",
        "formatting_enabled=false",
        "theme_mode=DARK",
    )

    /** [lines] joined with newlines and written verbatim as UTF-8. */
    private fun fileWithText(lines: List<String>): File =
        newFile().apply { writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8) }

    @Test
    fun `a file written before the flag existed loads it as off and keeps the other keys`() {
        val loaded = store(fileWithText(nineKeyLines)).load()

        assertFalse("a file without the key must load the flag as off", loaded.microphoneAsked)
        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }

    @Test
    fun `the flag survives a save and a new store over the same file`() {
        val file = newFile()

        store(file).save(validSettings().copy(microphoneAsked = true))
        assertTrue("the flag did not survive a new store", store(file).load().microphoneAsked)

        store(file).save(validSettings().copy(microphoneAsked = false))
        assertFalse("the flag did not come back off", store(file).load().microphoneAsked)
    }

    @Test
    fun `the saved file carries the key microphone_asked with the value true`() {
        val file = newFile()

        store(file).save(validSettings().copy(microphoneAsked = true))

        // The literal line, read from the file text, so a renamed key constant is caught.
        val rawText = file.readText(Charsets.UTF_8)
        assertTrue(
            "the saved file has no microphone_asked=true line:\n$rawText",
            rawText.lines().any { it == "microphone_asked=true" },
        )
    }

    @Test
    fun `the saved file carries the key microphone_asked with the value false`() {
        val file = newFile()

        store(file).save(validSettings().copy(microphoneAsked = false))

        val rawText = file.readText(Charsets.UTF_8)
        assertTrue(
            "the saved file has no microphone_asked=false line:\n$rawText",
            rawText.lines().any { it == "microphone_asked=false" },
        )
    }

    @Test
    fun `a value that is not a boolean loads as off and costs no other key`() {
        val loaded = store(fileWithText(nineKeyLines + "microphone_asked=maybe")).load()

        assertFalse("a value that is not a boolean must load as off", loaded.microphoneAsked)
        assertEquals("mode", SttMode.SERVER, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }

    @Test
    fun `the codec lists the new key among the keys it writes and writes ten`() {
        val written = SettingsPropertiesCodec.writtenKeys()

        assertTrue("microphone_asked is not among the written keys: $written", written.contains("microphone_asked"))
        assertEquals("the number of written keys", 10, written.size)
    }

    @Test
    fun `the flag does not disturb the key reference`() {
        val keystore = FakeKeystore()
        val file = newFile()

        store(file, keystore).save(validSettings().copy(apiKeyRef = "REFFLAG12345", microphoneAsked = true))
        val reloaded = store(file, keystore).load()

        assertEquals("the key reference after a reload", "REFFLAG12345", reloaded.apiKeyRef)
        assertTrue("the flag after a reload", reloaded.microphoneAsked)
    }
}
