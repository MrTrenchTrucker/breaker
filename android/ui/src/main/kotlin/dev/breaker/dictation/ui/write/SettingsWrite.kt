package dev.breaker.dictation.ui.write

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore

/** What happened when one edit of the stored settings was attempted. */
internal sealed interface WriteResult {
    /** The edit changed the settings and they were saved; [settings] is what was saved. */
    data class Saved(val settings: AppSettings) : WriteResult

    /** The edit left the settings as they were, so nothing was saved; [settings] is what was loaded. */
    data class Unchanged(val settings: AppSettings) : WriteResult

    /** The stored settings could not be read, so nothing was edited and nothing was saved. */
    data object FailedToLoad : WriteResult

    /** The edited settings could not be saved; the store was not changed by this call. */
    data object FailedToSave : WriteResult

    /** The edit refused its input with an [IllegalArgumentException], so nothing was saved. */
    data object Refused : WriteResult
}

/**
 * Changes the stored settings by one edit, through the store and nowhere else.
 *
 * The settings are read fresh on every call, never taken from a copy kept
 * earlier, so a change made by another writer is not overwritten with old
 * values. The order is fixed: load, edit, compare, save. A failed load saves
 * nothing and never falls back to defaults. An edit that throws
 * [IllegalArgumentException] is [WriteResult.Refused]; any other throwable from
 * the edit is not caught. An edit that returns the settings unchanged is
 * [WriteResult.Unchanged] and does not call `save`. A store that throws from
 * `load` or `save`, whatever the exception type, is a failure of the store and
 * never a refused value. Only exceptions are caught; an [Error] passes through.
 * No exception message is read or kept, so a message that carries a value or an
 * address can never reach the screen.
 *
 * Call it from the main thread only; it does no locking.
 */
internal fun writeThrough(store: SettingsStore, edit: (AppSettings) -> AppSettings): WriteResult {
    val loaded = try {
        store.load()
    } catch (loadFailure: Exception) {
        return WriteResult.FailedToLoad
    }
    val next = try {
        edit(loaded)
    } catch (refusal: IllegalArgumentException) {
        return WriteResult.Refused
    }
    if (next == loaded) return WriteResult.Unchanged(loaded)
    try {
        store.save(next)
    } catch (saveFailure: Exception) {
        return WriteResult.FailedToSave
    }
    return WriteResult.Saved(next)
}
