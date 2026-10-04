package dev.breaker.dictation.ui.theme

/** The bits of a `uiMode` value that say whether the phone is in night mode. */
private const val NIGHT_MASK = 0x30

/** The night bits when the phone is in night mode (dark). */
private const val NIGHT_YES = 0x20

/**
 * Whether the phone is showing its dark scheme, from the `uiMode` of its configuration.
 *
 * Only the night bits are read; every other bit is ignored. The night bits are
 * 0x20 for night, 0x10 for not night and 0x00 when the phone does not say. Only
 * 0x20 is dark; "not night", "does not say" and the unused value 0x30 all count
 * as light. The function takes the number so it needs no Android class.
 */
internal fun isNightMode(uiMode: Int): Boolean = (uiMode and NIGHT_MASK) == NIGHT_YES
