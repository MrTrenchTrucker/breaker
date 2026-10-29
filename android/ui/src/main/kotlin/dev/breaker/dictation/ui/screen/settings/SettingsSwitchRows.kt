package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ShakeWakeMode
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentCell
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.screen.TrimStripe
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The dictation block of the settings screen and the shake to wake row inside it.
 *
 * The block holds the preload switch, the shake to wake choice, the keyboard tile
 * switch and the formatting switch, with a hairline between each two rows. The
 * shake to wake choice is one row of three cells; each cell asks for its mode by
 * name under the shake key, the same way a switch asks for its value.
 */

/** The dictation block: a heading, then the rows in order with a hairline between each two. */
internal fun switchesBlock(held: AppSettings): Box = Box(
    id = SettingsIds.SWITCHES_BLOCK,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
    children = listOf(
        sectionLabel(SettingsIds.SWITCHES_HEADING, SettingsTexts.DICTATION_HEADING),
        preloadRow(held),
        TrimStripe(SettingsIds.HAIRLINE_PRELOAD),
        shakeWakeBlock(held),
        TrimStripe(SettingsIds.HAIRLINE_WAKE),
        keyboardRow(held),
        TrimStripe(SettingsIds.HAIRLINE_KEYBOARD),
        formattingRow(held),
    ),
)

private fun preloadRow(held: AppSettings): SwitchRow =
    switchRow(SettingsIds.SWITCH_PRELOAD, SettingsTexts.PRELOAD_LABEL, SettingsTexts.PRELOAD_HELP, SettingsIds.KEY_PRELOAD, held.preloadModel)

private fun keyboardRow(held: AppSettings): SwitchRow =
    switchRow(SettingsIds.SWITCH_SHOW_TILE, SettingsTexts.SHOW_TILE_LABEL, SettingsTexts.SHOW_TILE_HELP, SettingsIds.KEY_SHOW_TILE, held.showTileOnKeyboard)

private fun formattingRow(held: AppSettings): SwitchRow =
    switchRow(SettingsIds.SWITCH_FORMATTING, SettingsTexts.FORMATTING_LABEL, SettingsTexts.FORMATTING_HELP, SettingsIds.KEY_FORMATTING, held.formattingEnabled)

/**
 * The shake to wake row: its label, the three modes as cells with the stored one
 * selected, and the line that says what each mode does.
 */
private fun shakeWakeBlock(held: AppSettings): Box = Box(
    id = SettingsIds.SHAKE_WAKE_BLOCK,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
    children = listOf(
        bodyLabel(SettingsIds.SHAKE_WAKE_LABEL_ID, SettingsTexts.SHAKE_WAKE_LABEL, PaletteSlot.TEXT),
        SegmentedRow(
            id = SettingsIds.SHAKE_WAKE_CELLS,
            cells = ShakeWakeMode.entries.map { shakeWakeCell(it, held) },
        ),
        bodyLabel(SettingsIds.SHAKE_WAKE_HELP_ID, SettingsTexts.SHAKE_WAKE_HELP, PaletteSlot.TEXT_MUTED),
    ),
)

/** One shake to wake cell: its words, the mode it asks for by name, and whether it is the stored choice. */
private fun shakeWakeCell(mode: ShakeWakeMode, held: AppSettings): SegmentCell = SegmentCell(
    id = SettingsIds.SHAKE_WAKE_CELL_PREFIX + mode.name,
    text = shakeWakeWord(mode),
    selected = held.shakeWakeMode == mode,
    intent = ScreenIntent.SetSetting(SettingsIds.KEY_SHAKE_WAKE, mode.name),
)

private fun shakeWakeWord(mode: ShakeWakeMode): String = when (mode) {
    ShakeWakeMode.ALWAYS -> SettingsTexts.SHAKE_ALWAYS
    ShakeWakeMode.KEYBOARD_ONLY -> SettingsTexts.SHAKE_KEYBOARD_ONLY
    ShakeWakeMode.OFF -> SettingsTexts.SHAKE_OFF
}

/** A switch: its label and line, its current state, and the opposite state as the value its intent asks for. */
private fun switchRow(id: String, label: String, help: String, key: String, current: Boolean): SwitchRow =
    SwitchRow(
        id = id,
        label = label,
        help = help,
        on = current,
        intent = ScreenIntent.SetSetting(key, (!current).toString()),
    )
