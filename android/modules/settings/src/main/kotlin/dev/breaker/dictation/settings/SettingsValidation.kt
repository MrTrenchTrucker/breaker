package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.TilePosition
import java.util.Locale

/**
 * Per-key recovery for settings read out of a file, and nothing else.
 *
 * A hand-edited or truncated settings file must not be able to take the whole
 * settings object down with it, so **a bad value costs you that key only** —
 * proved by `SettingsValidationTest`, which writes a file with one unparsable
 * value and checks every other key still arrives.
 *
 * Four ways a value can be bad, all landing on the same fallback here:
 * it is missing; it does not parse (`wake_gesture_enabled=maybe`,
 * `tile x=abc`); the value parses but core rejects it (`language=` blank,
 * `tile x=1.5`, `theme_mode=NEON`); or the key was on a logical line the
 * file's own parser rejected, in which case the value never reaches this file
 * at all and the key arrives absent — `SettingsFileStore` drops that one line
 * and reads the rest.
 *
 * **Surrounding whitespace is not part of a value.** A URL, a model size and
 * a language tag have no meaningful trailing space, and the format keeps one
 * if the file has it, so [stringOrFallback] trims before deciding. A value
 * that was nothing but whitespace is still treated as absent, so it lands on
 * the same default an empty value does.
 *
 * **What this file deliberately does not check.** `AppSettings.init` already
 * requires a non-blank `modelSize` and a non-blank `language`, and
 * `TilePosition.init` already requires both axes within `0f..1f`. Re-deciding
 * those rules here would be a second copy to drift; instead [tilePosition] or
 * [floatOrFallback] range-checks a coordinate *before* constructing a
 * `TilePosition`, because an invalid one throws on construction and must never
 * be built even to be thrown away. `SettingsValidationTest` covers that those
 * core rules still hold after a save/load round trip.
 */
internal object SettingsValidation {

    /**
     * [raw] as a string, trimmed, or [fallback] when it is absent or blank.
     *
     * Blank is treated as absent because a blank `language` is exactly what a
     * half-finished hand edit leaves behind, and core would reject it.
     *
     * A present value is trimmed, because the format keeps trailing whitespace
     * and a hand-edited `server_url=https://box.local   ` is a typo in the
     * file rather than a server address with three spaces on the end of it.
     */
    fun stringOrFallback(raw: String?, fallback: String): String {
        val trimmed = raw?.trim()
        return if (trimmed.isNullOrEmpty()) fallback else trimmed
    }

    /**
     * [raw] as a boolean, or [fallback] when it is absent or not `true`/`false`.
     *
     * The name is matched case-insensitively, exactly as [enumOrFallback]
     * matches an enum name, and for the same reason: the file exists to survive
     * hand edits, and a hand editor types `FALSE` as readily as `false`. The
     * case fold is pinned to [Locale.ROOT] so a device whose default locale
     * folds `I` differently cannot turn one written word into another.
     *
     * Surrounding whitespace is ignored, and the value is trimmed before it is
     * folded — a file written with a trailing space reads as the word it spells
     * rather than as a value nothing recognises.
     */
    fun booleanOrFallback(raw: String?, fallback: Boolean): Boolean =
        when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "true" -> true
            "false" -> false
            else -> fallback
        }

    /**
     * [raw] as a float, or [fallback] when it is absent or unparsable.
     *
     * Always [String.toFloatOrNull] and [Float.toString]: both are locale-
     * independent by definition, so a value written on one phone reads back
     * identically on a phone whose locale uses a decimal comma.
     */
    fun floatOrFallback(raw: String?, fallback: Float): Float {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return fallback
        val parsed = trimmed.toFloatOrNull() ?: return fallback
        return if (parsed.isFinite()) parsed else fallback
    }

    /** [raw] as one of [values], or [fallback] when it is absent or an unknown name. */
    fun <E : Enum<E>> enumOrFallback(raw: String?, values: Array<E>, fallback: E): E {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return fallback
        return values.firstOrNull { it.name.equals(trimmed, ignoreCase = true) } ?: fallback
    }

    /**
     * The tile position named by [rawX]/[rawY], or [fallback].
     *
     * Both coordinates must be present, parsable and within `0f..1f`; a pair
     * where only one axis is good is not a position the file describes, so the
     * whole pair falls back rather than mixing a saved axis with a default one.
     * The range test happens here, before construction, so an out-of-range
     * pair is never handed to the `TilePosition` constructor that would throw.
     */
    fun tilePositionOrFallback(rawX: String?, rawY: String?, fallback: TilePosition): TilePosition {
        val x = inUnitRange(rawX)
        val y = inUnitRange(rawY)
        return if (x == null || y == null) fallback else TilePosition(x, y)
    }

    /** [raw] as a finite float within `0f..1f`, or null when it is not one. */
    private fun inUnitRange(raw: String?): Float? {
        val parsed = floatOrFallback(raw, Float.NaN)
        return if (parsed in 0f..1f) parsed else null
    }
}
