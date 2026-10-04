package dev.breaker.dictation.ui.theme

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.ui.write.WriteResult
import dev.breaker.dictation.ui.write.writeThrough
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import dev.breaker.shared.tokens.TruckingTokens

/**
 * The theme as the screen needs it.
 *
 * [stored] is the choice kept in the settings (follow the phone, light or dark),
 * [shown] is the light or dark scheme actually drawn, and [loaded] is false
 * until the settings have been read at least once.
 */
internal data class ThemeState(
    val stored: StoredThemeMode,
    val shown: ShownThemeMode,
    val loaded: Boolean,
)

/** What a theme action did, for the caller to turn into a message or none. */
internal enum class ThemeOutcome {
    /** The choice was saved and the observers were told. */
    CHANGED,

    /** The stored choice was already the requested one; nothing was saved. */
    UNCHANGED,

    /** The settings could not be read; nothing was changed. */
    COULD_NOT_READ,

    /** The choice could not be saved; nothing was changed. */
    COULD_NOT_SAVE,
}

/**
 * The scheme to draw for a stored choice.
 *
 * `LIGHT` and `DARK` are drawn as stored. `SYSTEM` is drawn dark when the phone
 * is in night mode ([systemIsDark]) and light otherwise.
 */
internal fun shownMode(stored: StoredThemeMode, systemIsDark: Boolean): ShownThemeMode = when (stored) {
    StoredThemeMode.LIGHT -> ShownThemeMode.LIGHT
    StoredThemeMode.DARK -> ShownThemeMode.DARK
    StoredThemeMode.SYSTEM -> if (systemIsDark) ShownThemeMode.DARK else ShownThemeMode.LIGHT
}

/** The stored choice that pins [shown], the light or dark scheme. */
private fun pinned(shown: ShownThemeMode): StoredThemeMode = when (shown) {
    ShownThemeMode.LIGHT -> StoredThemeMode.LIGHT
    ShownThemeMode.DARK -> StoredThemeMode.DARK
}

/**
 * Holds the theme choice and changes it through the settings store.
 *
 * Nothing is kept by this class between runs: the choice lives in the stored
 * settings as `themeMode`, read through [settings] when the controller is made
 * and again on every action, and written back with every other setting left as
 * it was found. The constructor never throws. When the settings cannot be read
 * the state is "follow the phone" with [ThemeState.loaded] false, and nothing is
 * written.
 *
 * An action saves first, then updates its own state, then tells the observers,
 * so an observer that reads the store sees the new value. An action that fails
 * leaves the state and the observers alone. Call it from the main thread only.
 *
 * @param systemIsDark whether the phone was in night mode when the controller was made.
 */
internal class ThemeController(
    private val settings: SettingsStore,
    private val systemIsDark: Boolean,
) {
    private val observers = mutableListOf<(ThemeState) -> Unit>()

    /** The theme as last read or last saved. */
    var state: ThemeState = readState()
        private set

    /**
     * Switches to the opposite of the scheme now shown and stores it as light or
     * dark, never as "follow the phone".
     */
    fun toggle(): ThemeOutcome = write(pinned(TruckingTokens.toggled(state.shown)))

    /** Stores "follow the phone". */
    fun useSystem(): ThemeOutcome = write(StoredThemeMode.SYSTEM)

    /** Calls [observer] with the new state after each saved change; not at once. Adding the same instance twice has no effect. */
    fun addObserver(observer: (ThemeState) -> Unit) {
        if (observer !in observers) observers.add(observer)
    }

    /** Stops calling [observer]; does nothing if it was not added. */
    fun removeObserver(observer: (ThemeState) -> Unit) {
        observers.remove(observer)
    }

    private fun readState(): ThemeState {
        val stored = try {
            settings.load().themeMode
        } catch (loadFailure: Exception) {
            return ThemeState(StoredThemeMode.SYSTEM, shownMode(StoredThemeMode.SYSTEM, systemIsDark), loaded = false)
        }
        return ThemeState(stored, shownMode(stored, systemIsDark), loaded = true)
    }

    private fun write(target: StoredThemeMode): ThemeOutcome {
        return when (val result = writeThrough(settings) { it.copy(themeMode = target) }) {
            is WriteResult.Saved -> {
                state = ThemeState(target, shownMode(target, systemIsDark), loaded = true)
                notifyObservers()
                ThemeOutcome.CHANGED
            }
            is WriteResult.Unchanged -> {
                val stored = result.settings.themeMode
                state = ThemeState(stored, shownMode(stored, systemIsDark), loaded = true)
                ThemeOutcome.UNCHANGED
            }
            WriteResult.FailedToLoad -> ThemeOutcome.COULD_NOT_READ
            WriteResult.FailedToSave, WriteResult.Refused -> ThemeOutcome.COULD_NOT_SAVE
        }
    }

    private fun notifyObservers() {
        // A copy is walked so an observer may remove itself while it is called.
        for (observer in observers.toList()) {
            try {
                observer(state)
            } catch (observerFailure: Exception) {
                // One observer failing must not stop the others or undo the saved change.
            }
        }
    }
}
