package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.model.TilePosition
import java.util.Properties

/**
 * Translation between [AppSettings] and a [java.util.Properties] file.
 *
 * This is the **only** file in the module that knows a property key string.
 * Every other file passes [Properties] around, so the file format has exactly
 * one place to change and one place to test.
 *
 * **No credential in here.** [AppSettings.apiKeyRef] is a reference, not a
 * credential, and it is deliberately *not* one of the keys below: it travels
 * as the [decode] parameter instead, so [encode] physically cannot write a
 * reference — let alone a secret — into a file. `SettingsKeystoreRefTest`
 * proves the file that comes out of [encode] contains neither the reference nor
 * an `api_key` key.
 *
 * **Character set: UTF-8, both ways.** [encode] produces a [Properties] that
 * [SettingsFileStore] writes through a `Writer` opened with
 * `Charsets.UTF_8`, and [decode] reads back through a matching UTF-8 reader.
 * `Properties.store(Writer, …)` escapes what ISO-8859-1 could not carry and
 * writes everything else literally; the reader unescapes `\uXXXX`, so a
 * language tag or URL with non-ASCII text survives the round trip byte-for-byte.
 *
 * **No ordering guarantees.** [Properties] is an unordered hash table, and
 * `store()` writes a timestamp comment as its first line. Tests assert on
 * decoded values and on named keys, never on file bytes or line order.
 */
internal object SettingsPropertiesCodec {

    private const val KEY_MODE = "mode"
    private const val KEY_MODEL_SIZE = "model_size"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_WAKE_GESTURE_ENABLED = "wake_gesture_enabled"
    private const val KEY_TILE_POSITION = "tile_position"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_PRELOAD_MODEL = "preload_model"
    private const val KEY_FORMATTING_ENABLED = "formatting_enabled"
    private const val KEY_THEME_MODE = "theme_mode"

    /** The keys this module writes, in no particular order on disk. */
    private val WRITTEN_KEYS = arrayOf(
        KEY_MODE,
        KEY_MODEL_SIZE,
        KEY_SERVER_URL,
        KEY_WAKE_GESTURE_ENABLED,
        KEY_TILE_POSITION,
        KEY_LANGUAGE,
        KEY_PRELOAD_MODEL,
        KEY_FORMATTING_ENABLED,
        KEY_THEME_MODE,
    )

    /** Every key this codec writes; used by tests to prove nothing extra is written. */
    fun writtenKeys(): Set<String> = WRITTEN_KEYS.toSet()

    /**
     * [settings] as a [Properties], with no key for [AppSettings.apiKeyRef].
     *
     * A blank `server_url` is written as an empty value rather than dropped,
     * so the file shape is the same whichever way the user left it.
     */
    fun encode(settings: AppSettings): Properties {
        val properties = Properties()
        properties[KEY_MODE] = settings.mode.name
        properties[KEY_MODEL_SIZE] = settings.modelSize
        properties[KEY_SERVER_URL] = settings.serverUrl
        properties[KEY_WAKE_GESTURE_ENABLED] = settings.wakeGestureEnabled.toString()
        properties[KEY_TILE_POSITION] = encodeTilePosition(settings.tilePosition)
        properties[KEY_LANGUAGE] = settings.language
        properties[KEY_PRELOAD_MODEL] = settings.preloadModel.toString()
        properties[KEY_FORMATTING_ENABLED] = settings.formattingEnabled.toString()
        properties[KEY_THEME_MODE] = settings.themeMode.name
        return properties
    }

    /**
     * The settings described by [properties], with [apiKeyRef] carried through.
     *
     * [apiKeyRef] comes from the caller, never from [properties]: the file has
     * no key for it. `SettingsFileStore` supplies it from the `Keystore` port,
     * which is where it was persisted. [SettingsValidation] supplies each key's
     * default on a missing, unparsable or rejected value, so one bad key does
     * not discard the rest — `SettingsValidationTest`.
     */
    fun decode(properties: Properties, apiKeyRef: String? = null): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            mode = SettingsValidation.enumOrFallback(
                properties.getProperty(KEY_MODE), SttMode.entries.toTypedArray(), defaults.mode,
            ),
            // An id the registry does not name costs this key its default and
            // nothing else — the per-key contract every other key here keeps.
            modelSize = SettingsValidation.modelSizeOrFallback(
                properties.getProperty(KEY_MODEL_SIZE), defaults.modelSize,
            ),
            serverUrl = SettingsValidation.stringOrFallback(
                properties.getProperty(KEY_SERVER_URL), defaults.serverUrl,
            ),
            apiKeyRef = apiKeyRef,
            wakeGestureEnabled = SettingsValidation.booleanOrFallback(
                properties.getProperty(KEY_WAKE_GESTURE_ENABLED), defaults.wakeGestureEnabled,
            ),
            tilePosition = decodeTilePosition(properties, defaults.tilePosition),
            language = SettingsValidation.stringOrFallback(
                properties.getProperty(KEY_LANGUAGE), defaults.language,
            ),
            preloadModel = SettingsValidation.booleanOrFallback(
                properties.getProperty(KEY_PRELOAD_MODEL), defaults.preloadModel,
            ),
            formattingEnabled = SettingsValidation.booleanOrFallback(
                properties.getProperty(KEY_FORMATTING_ENABLED), defaults.formattingEnabled,
            ),
            themeMode = SettingsValidation.enumOrFallback(
                properties.getProperty(KEY_THEME_MODE), ThemeMode.entries.toTypedArray(), defaults.themeMode,
            ),
        )
    }

    /**
     * A tile position as two locale-independent decimal numbers.
     *
     * `Float.toString` is the one float formatting guaranteed not to consult
     * the default locale, so a position written under a comma-decimal locale
     * still parses on a dot-decimal one.
     */
    private fun encodeTilePosition(position: TilePosition): String =
        "${position.x.toString()},${position.y.toString()}"

    /** The tile position named by [KEY_TILE_POSITION], or [fallback]. */
    private fun decodeTilePosition(properties: Properties, fallback: TilePosition): TilePosition {
        val raw = properties.getProperty(KEY_TILE_POSITION)
        if (raw.isNullOrBlank()) return fallback
        val parts = raw.split(',')
        if (parts.size != 2) return fallback
        return SettingsValidation.tilePositionOrFallback(parts[0], parts[1], fallback)
    }
}
