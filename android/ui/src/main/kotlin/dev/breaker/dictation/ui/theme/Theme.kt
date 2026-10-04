package dev.breaker.dictation.ui.theme

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TokenColor
import dev.breaker.shared.tokens.TruckingMetrics
import dev.breaker.shared.tokens.TruckingPalette
import dev.breaker.shared.tokens.TruckingTokens
import dev.breaker.shared.tokens.TruckingType

/**
 * The token values of one mode, resolved for drawing.
 *
 * A screen asks for a [PaletteSlot] and gets a value that came from
 * [TruckingTokens]; a screen never holds a colour of its own. The mode here is
 * the two-value mode of the design tokens, light or dark. Every value is read
 * from the tokens, so a change to a token reaches every screen without an edit
 * in this module.
 */
internal class Theme(
    /** The mode this theme is resolved for. */
    val mode: ThemeMode,
) {
    /** The palette of [mode]. */
    val palette: TruckingPalette = TruckingTokens.palette(mode)

    /** The typeface family names. */
    val type: TruckingType = TruckingTokens.type

    /** The fixed dimensions, in dp: the touch-target floor and the corner radius. */
    val metrics: TruckingMetrics = TruckingTokens.metrics

    /**
     * The colour a [PaletteSlot] names in this mode.
     *
     * The `when` is exhaustive over the slots, so a slot without a token is a
     * compile error rather than a missing colour at run time.
     */
    fun color(slot: PaletteSlot): TokenColor = when (slot) {
        PaletteSlot.BACKGROUND -> palette.bg
        PaletteSlot.SURFACE -> palette.surface
        PaletteSlot.TEXT -> palette.text
        PaletteSlot.TEXT_MUTED -> palette.textMuted
        PaletteSlot.PRIMARY -> palette.primary
        PaletteSlot.PRIMARY_HOVER -> palette.primaryHover
        PaletteSlot.ACCENT -> palette.accent
        PaletteSlot.DANGER -> palette.danger
        PaletteSlot.TRIM -> palette.trim
        PaletteSlot.STATE_SENT -> palette.sent
        PaletteSlot.STATE_WARNING -> palette.warning
        PaletteSlot.STATE_DANGER -> palette.danger
    }

    /** Every slot with its colour in this mode. */
    fun colorsBySlot(): Map<PaletteSlot, TokenColor> =
        PaletteSlot.entries.associateWith { color(it) }

    override fun toString(): String = "Theme($mode)"
}

/**
 * The colour roles a screen may draw in.
 *
 * A slot is a name, never a value: the screen says "this is the danger role"
 * and the theme decides what danger looks like in the mode in force.
 */
internal enum class PaletteSlot {
    BACKGROUND,
    SURFACE,
    TEXT,
    TEXT_MUTED,
    PRIMARY,
    PRIMARY_HOVER,
    ACCENT,
    DANGER,
    TRIM,
    STATE_SENT,
    STATE_WARNING,
    STATE_DANGER,
}

/** Builds the theme for a mode. */
internal object Themes {
    /** The theme for [mode]. */
    fun of(mode: ThemeMode): Theme = Theme(mode)
}
