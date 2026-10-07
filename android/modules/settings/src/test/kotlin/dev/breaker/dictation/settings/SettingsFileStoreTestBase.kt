package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.model.TilePosition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

/**
 * The on-disk contract these tests assume, in one place.
 *
 * The file is a `java.util.Properties` document keyed by the nine keys below;
 * `api_key` is deliberately NOT among them (see `SettingsKeystoreRefTest`).
 * `Properties.store()` writes a timestamp comment first and is an unordered
 * hashtable, so nothing here compares whole-file bytes or key order.
 */
internal const val KEY_MODE = "mode"
internal const val KEY_MODEL_SIZE = "model_size"
internal const val KEY_SERVER_URL = "server_url"
internal const val KEY_WAKE_GESTURE = "wake_gesture_enabled"
internal const val KEY_TILE_POSITION = "tile_position"
internal const val KEY_LANGUAGE = "language"
internal const val KEY_PRELOAD_MODEL = "preload_model"
internal const val KEY_FORMATTING = "formatting_enabled"
internal const val KEY_THEME_MODE = "theme_mode"

/** Every key a saved settings file may carry. Nothing else is allowed. */
internal val THE_NINE_KEYS: Set<String> = setOf(
    KEY_MODE,
    KEY_MODEL_SIZE,
    KEY_SERVER_URL,
    KEY_WAKE_GESTURE,
    KEY_TILE_POSITION,
    KEY_LANGUAGE,
    KEY_PRELOAD_MODEL,
    KEY_FORMATTING,
    KEY_THEME_MODE,
)

/**
 * Substrings that make a property key credential-shaped, matched
 * case-insensitively. `secret` and `password` belong here for the same reason
 * `api` does; the earlier filter checked only the first three, which is part
 * of what this guard now covers.
 */
internal val CREDENTIAL_LIKE_SUBSTRINGS: List<String> =
    listOf("api", "key", "token", "secret", "password")

/**
 * The credential-shaped keys among [keys] — where [keys] are the keys parsed
 * out of a file on disk, never the keys the writer believed it wrote.
 *
 * The input has to come from the file. A guard handed the writer's own key
 * list can only ever see its own output, so it can never catch a key the
 * writer never intended to write, which is the only thing it is here for.
 */
internal fun credentialLikeKeysIn(keys: Collection<String>): Set<String> =
    keys.filterTo(mutableSetOf()) { key ->
        CREDENTIAL_LIKE_SUBSTRINGS.any { key.contains(it, ignoreCase = true) }
    }

/**
 * Shared fixture for the settings-file test classes.
 *
 * It carries **no `@Test` methods**: it exists only so the classes beside it
 * each own their cases, and so the fixture is written once. Every member here
 * is used by two or more of those classes, which is why it lives here rather
 * than being copied into each of them.
 *
 * The temporary-folder `@Rule` is public because JUnit requires a rule field to
 * be public; it is inherited by every subclass, so each class still gets a
 * fresh directory per test without declaring anything.
 *
 * The store is constructed as `SettingsFileStore(file, keystore)`.
 */
abstract class SettingsFileStoreTestBase {
    @get:Rule
    val temp = TemporaryFolder()

    /** A settings value that is non-default on every key the file carries. */
    protected fun validSettings(tile: TilePosition = TilePosition(0.25f, 0.75f)) = AppSettings(
        mode = SttMode.SERVER,
        modelSize = "medium",
        serverUrl = "https://box.local",
        apiKeyRef = null,
        wakeGestureEnabled = false,
        // Off-centre on purpose: the default is 0.5/0.5, so a value the store
        // never actually wrote could not satisfy this.
        tilePosition = tile,
        language = "de",
        preloadModel = false,
        formattingEnabled = false,
        themeMode = ThemeMode.DARK,
    )

    protected fun newFile(name: String = "settings.properties"): File =
        File(temp.newFolder(), name)

    protected fun store(file: File, keystore: Keystore = FakeKeystore()) =
        SettingsFileStore(file, keystore)

    /**
     * Writes every key [validSettings] describes at its non-default value,
     * except [key], which gets [value] — the shape a hand-edited or
     * half-migrated file has.
     */
    protected fun fileWith(key: String, value: String): File {
        val props = Properties()
        props.setProperty(KEY_MODE, SttMode.SERVER.name)
        props.setProperty(KEY_MODEL_SIZE, "medium")
        props.setProperty(KEY_SERVER_URL, "https://box.local")
        props.setProperty(KEY_WAKE_GESTURE, "false")
        props.setProperty(KEY_TILE_POSITION, "0.25,0.75")
        props.setProperty(KEY_LANGUAGE, "de")
        props.setProperty(KEY_PRELOAD_MODEL, "false")
        props.setProperty(KEY_FORMATTING, "false")
        props.setProperty(KEY_THEME_MODE, ThemeMode.DARK.name)
        props.setProperty(key, value)
        val file = newFile()
        file.outputStream().use { props.store(it, "test") }
        return file
    }

    /** Reads the saved file back as raw properties, bypassing the store. */
    protected fun rawPropertiesOf(file: File): Properties {
        val props = Properties()
        file.inputStream().use(props::load)
        return props
    }

    protected fun propertyOf(file: File, key: String): String =
        rawPropertiesOf(file).getProperty(key)

    /**
     * Every key [fileWith] wrote other than [exceptKey] still reads back the
     * value it was written with — [validSettings], which [fileWith] writes
     * verbatim and which is non-default on every key the file carries, so a key
     * that silently reset to its default cannot pass for one that was read.
     *
     * A store that discarded the whole file on one bad value would satisfy a
     * lone "this key got its default" assertion, so every fallback test
     * carries this alongside it.
     */
    protected fun assertEveryOtherKeySurvived(loaded: AppSettings, exceptKey: String) {
        val written = validSettings()
        val checked = mutableListOf<String>()

        if (exceptKey != KEY_MODE) {
            assertEquals("mode", written.mode, loaded.mode)
            checked += KEY_MODE
        }
        if (exceptKey != KEY_SERVER_URL) {
            assertEquals("server address", written.serverUrl, loaded.serverUrl)
            checked += KEY_SERVER_URL
        }
        if (exceptKey != KEY_WAKE_GESTURE) {
            assertEquals("wake gesture", written.wakeGestureEnabled, loaded.wakeGestureEnabled)
            checked += KEY_WAKE_GESTURE
        }
        if (exceptKey != KEY_TILE_POSITION) {
            assertEquals("tile x", written.tilePosition.x, loaded.tilePosition.x, 0f)
            assertEquals("tile y", written.tilePosition.y, loaded.tilePosition.y, 0f)
            checked += KEY_TILE_POSITION
        }
        if (exceptKey != KEY_LANGUAGE) {
            assertEquals("language", written.language, loaded.language)
            checked += KEY_LANGUAGE
        }
        if (exceptKey != KEY_PRELOAD_MODEL) {
            assertEquals("preload model", written.preloadModel, loaded.preloadModel)
            checked += KEY_PRELOAD_MODEL
        }
        if (exceptKey != KEY_FORMATTING) {
            assertEquals("formatting", written.formattingEnabled, loaded.formattingEnabled)
            checked += KEY_FORMATTING
        }
        if (exceptKey != KEY_THEME_MODE) {
            assertEquals("theme mode", written.themeMode, loaded.themeMode)
            checked += KEY_THEME_MODE
        }
        if (exceptKey != KEY_MODEL_SIZE) {
            assertEquals("model size", written.modelSize, loaded.modelSize)
            checked += KEY_MODEL_SIZE
        }

        assertEquals("a key was left unchecked", 8, checked.size)
    }
}
