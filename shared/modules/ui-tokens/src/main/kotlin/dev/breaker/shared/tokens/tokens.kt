package dev.breaker.shared.tokens

/*
 * Breaker's Trucking design tokens as plain Kotlin values.
 *
 * The web front end reads the same values from tokens.css, so the app and the
 * website are one design. This file has no Android or Compose import and no
 * dependency: a consumer maps these values onto its own UI types (colors as
 * ARGB ints, dimensions as dp numbers). The color properties are value-class
 * typed, so they are meant for Kotlin consumers; Java sees mangled accessor names.
 */

/**
 * A color token, held as packed opaque ARGB.
 *
 * The module card publishes CSS hex (`#1E7A46`); Android takes colors as an
 * `Int` with alpha in the high byte. A token color is always opaque: a value
 * whose alpha byte is not 0xFF is refused, so [hex], which leaves alpha out,
 * always describes the whole color.
 */
@JvmInline
value class TokenColor(val argb: Int) {
    init {
        require((argb ushr 24) == 0xFF) {
            "a token color is opaque ARGB; the alpha byte is ${argb ushr 24}, not 255"
        }
    }

    /** The color as `#RRGGBB`. */
    val hex: String
        get() = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    override fun toString(): String = "TokenColor($hex)"
}

/** Which of the two palettes is in force. */
enum class ThemeMode { LIGHT, DARK }

/**
 * The palette tokens of one mode, plus the sent and warning state colors.
 *
 * [danger] is both the destructive-action color and the complete-failure state
 * color: the card's two tables give the same value for it in each mode.
 * [sent] is the state color for a committed transcription. It equals [primary]
 * in value but is a separate token; [accent] is the highlight color and is not
 * the sent state.
 */
data class TruckingPalette(
    val bg: TokenColor,
    val surface: TokenColor,
    val text: TokenColor,
    val textMuted: TokenColor,
    val primary: TokenColor,
    val primaryHover: TokenColor,
    val accent: TokenColor,
    val danger: TokenColor,
    val trim: TokenColor,
    val sent: TokenColor,
    val warning: TokenColor,
)

/**
 * Typeface family names.
 *
 * Only names are carried: bundling a typeface is an asset decision, not a
 * design token. The design allows either of two condensed display families for
 * headings; they are given in the order the CSS font stack lists them,
 * [displayFamily] first and [displayAlternateFamily] second.
 */
data class TruckingType(
    val displayFamily: String,
    val displayAlternateFamily: String,
    val bodyFamily: String,
    val monoFamily: String,
)

/** The three responsive bands. */
enum class LayoutBand { MOBILE, TABLET, DESKTOP }

/**
 * The fixed dimensions, in dp.
 *
 * The design states them in px; the same numbers are used as dp on Android.
 */
data class TruckingMetrics(
    /** Hard edges: no corner is rounder than this. */
    val cornerRadiusDp: Int,
    /** The smallest touch target. */
    val minTouchTargetDp: Int,
    /** Widths below this are mobile. */
    val mobileBreakpointDp: Int,
    /** Widths up to and including this are tablet; wider is desktop. */
    val tabletBreakpointDp: Int,
) {
    init {
        require(mobileBreakpointDp < tabletBreakpointDp) {
            "the mobile breakpoint $mobileBreakpointDp must be below the tablet breakpoint $tabletBreakpointDp"
        }
    }

    /**
     * The band a width falls into: below [mobileBreakpointDp] is mobile, from there
     * through [tabletBreakpointDp] inclusive is tablet, wider is desktop.
     *
     * The width may be fractional and is compared exactly, so 639.5 is mobile and
     * 1024.5 is desktop. Do not round a width before asking: rounding up puts 639.5
     * in tablet, and rounding down puts 1024.5 in tablet, so neither direction is
     * right at both edges. Positive and negative infinity are defined (desktop and
     * mobile).
     *
     * @throws IllegalArgumentException when [widthDp] is NaN, which has no band.
     */
    fun bandFor(widthDp: Double): LayoutBand {
        require(!widthDp.isNaN()) { "a width of $widthDp dp is not a number, so it has no band" }
        return when {
            widthDp < mobileBreakpointDp -> LayoutBand.MOBILE
            widthDp <= tabletBreakpointDp -> LayoutBand.TABLET
            else -> LayoutBand.DESKTOP
        }
    }

    /** The band of a [Float] width, such as Compose's `Dp.value`: the same rule as the [Double] overload. */
    fun bandFor(widthDp: Float): LayoutBand = bandFor(widthDp.toDouble())

    /** The band of a whole-dp width: the same rule as the [Double] overload. */
    fun bandFor(widthDp: Int): LayoutBand = bandFor(widthDp.toDouble())
}

/**
 * The number of segments the LED bar meter may show, as an inclusive range:
 * at least 1, and [minSegments] no greater than [maxSegments].
 */
data class TruckingLedBar(
    val minSegments: Int,
    val maxSegments: Int,
) {
    init {
        require(minSegments >= 1) { "the LED bar minimum $minSegments segments is below 1" }
        require(minSegments <= maxSegments) {
            "the LED bar minimum $minSegments segments is above its maximum $maxSegments"
        }
    }

    val segmentRange: IntRange
        get() = minSegments..maxSegments
}

/** The Trucking token values the module card publishes. */
object TruckingTokens {
    val LIGHT = TruckingPalette(
        bg = TokenColor(0xFFFFFFFF.toInt()),
        surface = TokenColor(0xFFF4F6F4.toInt()),
        text = TokenColor(0xFF111417.toInt()),
        textMuted = TokenColor(0xFF5A6368.toInt()),
        primary = TokenColor(0xFF1E7A46.toInt()),
        primaryHover = TokenColor(0xFF16603A.toInt()),
        accent = TokenColor(0xFF257F49.toInt()),
        danger = TokenColor(0xFFC0392B.toInt()),
        trim = TokenColor(0xFF000000.toInt()),
        sent = TokenColor(0xFF1E7A46.toInt()),
        warning = TokenColor(0xFFA55713.toInt()),
    )

    val DARK = TruckingPalette(
        bg = TokenColor(0xFF0E1113.toInt()),
        surface = TokenColor(0xFF161B1E.toInt()),
        text = TokenColor(0xFFF2F5F2.toInt()),
        textMuted = TokenColor(0xFF9AA5A0.toInt()),
        primary = TokenColor(0xFF2E9E5B.toInt()),
        primaryHover = TokenColor(0xFF3BBE6E.toInt()),
        accent = TokenColor(0xFF34A853.toInt()),
        danger = TokenColor(0xFFE74C3C.toInt()),
        trim = TokenColor(0xFFFFFFFF.toInt()),
        sent = TokenColor(0xFF2E9E5B.toInt()),
        warning = TokenColor(0xFFF39C12.toInt()),
    )

    /** The palette for [mode]. */
    fun palette(mode: ThemeMode): TruckingPalette = when (mode) {
        ThemeMode.LIGHT -> LIGHT
        ThemeMode.DARK -> DARK
    }

    /** The mode on the other side of the light/dark toggle. */
    fun toggled(mode: ThemeMode): ThemeMode = when (mode) {
        ThemeMode.LIGHT -> ThemeMode.DARK
        ThemeMode.DARK -> ThemeMode.LIGHT
    }

    val type = TruckingType(
        displayFamily = "Anton",
        displayAlternateFamily = "Oswald",
        bodyFamily = "Inter",
        monoFamily = "JetBrains Mono",
    )

    val metrics = TruckingMetrics(
        cornerRadiusDp = 4,
        minTouchTargetDp = 48,
        mobileBreakpointDp = 640,
        tabletBreakpointDp = 1024,
    )

    val ledBar = TruckingLedBar(minSegments = 12, maxSegments = 16)
}
