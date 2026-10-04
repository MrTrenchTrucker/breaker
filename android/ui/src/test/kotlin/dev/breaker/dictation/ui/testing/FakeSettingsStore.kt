package dev.breaker.dictation.ui.testing

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.dictation.core.port.SettingsStore

/** A text that only a failing [FakeSettingsStore] puts in its exception message. */
internal const val FAILURE_MARKER = "BOOM-7731"

/** An address that only a failing [FakeSettingsStore] puts in its exception message. */
internal const val FAILURE_URL = "https://boom.example.invalid/7731"

/**
 * Settings in which every field differs from `AppSettings()`.
 *
 * A test that starts from these cannot pass by writing or showing the defaults.
 */
internal val NONDEFAULT: AppSettings = AppSettings(
    mode = SttMode.SERVER,
    modelSize = "medium",
    serverUrl = "https://srv.example.invalid:8443",
    apiKeyRef = "REF-9f3k",
    wakeGestureEnabled = false,
    tilePosition = TilePosition(0.2f, 0.8f),
    language = "de",
    preloadModel = false,
    formattingEnabled = false,
    themeMode = ThemeMode.LIGHT,
)

/**
 * A [SettingsStore] held in memory that counts every call and can be made to fail.
 *
 * [loadAttempts] and [saveAttempts] count every call, failed or not, so a test
 * can prove a failure was reached. [saves] holds the settings of each save that
 * succeeded, in order. [events] is an ordered log that holds "save" for each
 * successful save and any note a test appends, so a test can check what came
 * before what. While [failLoad] or [failSave] is set, the call throws an
 * [IllegalStateException] whose message holds [FAILURE_MARKER] and [FAILURE_URL];
 * a failed save leaves the stored settings and [saves] unchanged.
 */
internal class FakeSettingsStore(initial: AppSettings) : SettingsStore {
    /** The settings now stored; reading this does not count as a load. */
    var current: AppSettings = initial
        private set

    var loadAttempts: Int = 0
        private set
    var saveAttempts: Int = 0
        private set

    /** The settings of each successful save, in order. */
    val saves: MutableList<AppSettings> = mutableListOf()

    /** "save" for each successful save, plus any note a test appends. */
    val events: MutableList<String> = mutableListOf()

    var failLoad: Boolean = false
    var failSave: Boolean = false

    /** Replaces the stored settings as another writer would; counts as neither a load nor a save. */
    fun replaceStored(settings: AppSettings) {
        current = settings
    }

    override fun load(): AppSettings {
        loadAttempts++
        if (failLoad) throw IllegalStateException("load failed: $FAILURE_MARKER $FAILURE_URL")
        return current
    }

    override fun save(settings: AppSettings) {
        saveAttempts++
        if (failSave) throw IllegalStateException("save failed: $FAILURE_MARKER $FAILURE_URL")
        current = settings
        saves.add(settings)
        events.add("save")
    }
}
