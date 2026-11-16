package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.dictation.core.model.WakeGestureMode
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
 * **The server list: three flat keys, not one escaped value.**
 * `server_address_1..3` hold the ordered list (order is probe priority) and
 * `server_last_good` holds the address that answered last. `server_url` stays as
 * entry 1 for every existing reader, and [decode] migrates an install that holds
 * only `server_url` to a one-entry list.
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
    private const val KEY_SERVER_ADDRESS_1 = "server_address_1"
    private const val KEY_SERVER_ADDRESS_2 = "server_address_2"
    private const val KEY_SERVER_ADDRESS_3 = "server_address_3"
    private const val KEY_SERVER_LAST_GOOD = "server_last_good"
    private const val KEY_WAKE_GESTURE_MODE = "wake_gesture_mode"
    private const val KEY_WAKE_GESTURE_ENABLED = "wake_gesture_enabled"
    private const val KEY_TILE_POSITION = "tile_position"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_PRELOAD_MODEL = "preload_model"
    private const val KEY_FORMATTING_ENABLED = "formatting_enabled"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_MICROPHONE_ASKED = "microphone_asked"

    /** The keys this module writes, in no particular order on disk. */
    private val WRITTEN_KEYS = arrayOf(
        KEY_MODE,
        KEY_MODEL_SIZE,
        KEY_SERVER_URL,
        KEY_SERVER_ADDRESS_1,
        KEY_SERVER_ADDRESS_2,
        KEY_SERVER_ADDRESS_3,
        KEY_SERVER_LAST_GOOD,
        KEY_WAKE_GESTURE_MODE,
        KEY_TILE_POSITION,
        KEY_LANGUAGE,
        KEY_PRELOAD_MODEL,
        KEY_FORMATTING_ENABLED,
        KEY_THEME_MODE,
        KEY_MICROPHONE_ASKED,
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
        val entries = entriesOf(settings)
        properties[KEY_MODE] = settings.mode.name
        properties[KEY_MODEL_SIZE] = settings.modelSize
        // server_url stays entry 1: the fallback below makes the stored single
        // address and the list's first entry the same string.
        properties[KEY_SERVER_URL] = entries.getOrElse(0) { "" }
        properties[KEY_SERVER_ADDRESS_1] = entries.getOrElse(0) { "" }
        properties[KEY_SERVER_ADDRESS_2] = entries.getOrElse(1) { "" }
        properties[KEY_SERVER_ADDRESS_3] = entries.getOrElse(2) { "" }
        properties[KEY_SERVER_LAST_GOOD] = settings.serverLastGood
        properties[KEY_WAKE_GESTURE_MODE] = settings.wakeGestureMode.name
        properties[KEY_TILE_POSITION] = encodeTilePosition(settings.tilePosition)
        properties[KEY_LANGUAGE] = settings.language
        properties[KEY_PRELOAD_MODEL] = settings.preloadModel.toString()
        properties[KEY_FORMATTING_ENABLED] = settings.formattingEnabled.toString()
        properties[KEY_THEME_MODE] = settings.themeMode.name
        properties[KEY_MICROPHONE_ASKED] = settings.microphoneAsked.toString()
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
        val entries = decodeServerAddresses(
            properties.getProperty(KEY_SERVER_ADDRESS_1),
            properties.getProperty(KEY_SERVER_ADDRESS_2),
            properties.getProperty(KEY_SERVER_ADDRESS_3),
            properties.getProperty(KEY_SERVER_URL),
        )
        return AppSettings(
            mode = SettingsValidation.enumOrFallback(
                properties.getProperty(KEY_MODE), SttMode.entries.toTypedArray(), defaults.mode,
            ),
            // An id the registry does not name costs this key its default and
            // nothing else — the per-key contract every other key here keeps.
            modelSize = SettingsValidation.modelSizeOrFallback(
                properties.getProperty(KEY_MODEL_SIZE), defaults.modelSize,
            ),
            // server_url is entry 1 of the list, so the two are read together.
            //
            // CANONICAL FORM: the list holds TWO OR MORE entries, or it is EMPTY.
            // A single address lives in `serverUrl` alone, exactly as it did before
            // this RC, so a legacy one-address file decodes to the same AppSettings
            // it always did and `serverAddressList()` still answers [serverUrl].
            // Nothing downstream changes: every reader already goes through
            // `serverAddressList()`, which falls back to `serverUrl`.
            serverUrl = entries.getOrElse(0) { "" },
            serverAddresses = if (entries.size >= 2) entries else emptyList(),
            apiKeyRef = apiKeyRef,
            wakeGestureMode = SettingsValidation.wakeGestureModeOrFallback(
                properties.getProperty(KEY_WAKE_GESTURE_MODE),
                properties.getProperty(KEY_WAKE_GESTURE_ENABLED),
                defaults.wakeGestureMode,
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
            microphoneAsked = SettingsValidation.booleanOrFallback(
                properties.getProperty(KEY_MICROPHONE_ASKED), defaults.microphoneAsked,
            ),
            serverLastGood = SettingsValidation.stringOrFallback(
                properties.getProperty(KEY_SERVER_LAST_GOOD), defaults.serverLastGood,
            ),
        )
    }

    /**
     * The list to write: [AppSettings.serverAddresses], or the stored single
     * [AppSettings.serverUrl] when the list is empty.
     *
     * The fallback keeps a caller that only sets `serverUrl` (the settings
     * screen today) from losing the address: entry 1 becomes `serverUrl`, so
     * `server_url` and the list stay equal and the file is still readable by an
     * older app. It mirrors [decodeServerAddresses] exactly.
     */
    private fun entriesOf(settings: AppSettings): List<String> =
        settings.serverAddresses.ifEmpty {
            listOfNotNull(settings.serverUrl.takeIf { it.isNotBlank() })
        }

    /**
     * The ordered list named by the three `server_address_1..3` keys, migrated
     * from the old `server_url` key.
     *
     * Each value is trimmed and **every** blank entry is dropped, wherever it
     * sits: a blank entry means "not set", so a person who cleared entry 1 has a
     * list that starts at entry 2, and trailing blanks are the same case. Keeping
     * a leading blank would give a list whose entry 1 is empty, which is neither
     * a usable address nor the blank install. The empty list is that blank
     * install: all three keys blank and no old key. A new key that is present
     * wins over the old one, so an app that already wrote the list never reads a
     * stale `server_url`.
     */
    private fun decodeServerAddresses(
        first: String?,
        second: String?,
        third: String?,
        legacyServerUrl: String?,
    ): List<String> {
        val kept = listOf(first, second, third)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
        if (kept.isNotEmpty()) return kept
        val legacy = legacyServerUrl?.trim().orEmpty()
        return if (legacy.isEmpty()) emptyList() else listOf(legacy)
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
