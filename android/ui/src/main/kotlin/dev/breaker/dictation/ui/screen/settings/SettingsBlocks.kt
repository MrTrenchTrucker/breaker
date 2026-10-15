package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentCell
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState

/*
 * The four blocks of the settings screen, each one a node built from the values
 * it is handed and from nothing else.
 *
 * Every block is a card on the surface slot. Headings and help lines are drawn in
 * the muted slot, values in the text slot, and every choice is a segmented cell or
 * a switch row. No function here names a colour of its own.
 */

/** The node ids and the stored keys of the settings screen, in one place. */
internal object SettingsIds {
    const val TITLE_ID = "settings.title"
    const val STRIPE_ID = "settings.stripe"
    const val NOTICE_ID = "settings.notice"

    const val THEME_BLOCK = "settings.theme"
    const val THEME_HEADING = "settings.theme.heading"
    const val THEME_CELLS = "settings.theme.cells"
    const val THEME_SYSTEM = "settings.theme.system"
    const val THEME_LIGHT = "settings.theme.light"
    const val THEME_DARK = "settings.theme.dark"
    const val THEME_HELP = "settings.theme.help"

    const val ROUTING_BLOCK = "settings.routing"
    const val ROUTING_HEADING = "settings.routing.heading"
    const val ROUTING_CELLS = "settings.routing.cells"
    const val ROUTING_CELL_PREFIX = "settings.routing."
    const val ROUTING_HELP = "settings.routing.help"

    const val SWITCHES_BLOCK = "settings.switches"
    const val SWITCHES_HEADING = "settings.switches.heading"
    const val SWITCH_PRELOAD = "settings.preloadModel"
    const val SWITCH_WAKE = "settings.wakeGestureEnabled"
    const val SWITCH_FORMATTING = "settings.formattingEnabled"

    const val VALUES_BLOCK = "settings.values"
    const val VALUES_HEADING = "settings.values.heading"
    const val MODEL_ROW = "settings.model"
    const val MODEL_LABEL = "settings.model.label"
    const val MODEL_VALUE = "settings.model.value"
    const val SERVER_ROW = "settings.server"
    const val SERVER_LABEL = "settings.server.label"
    const val SERVER_FIELD = "settings.server.field"
    const val API_KEY_ROW = "settings.apiKey"
    const val API_KEY_LABEL = "settings.apiKey.label"
    const val API_KEY_VALUE = "settings.apiKey.value"

    /** The stored keys the three switches write; the screen reads them back when it edits. */
    const val KEY_PRELOAD = "preloadModel"
    const val KEY_WAKE_GESTURE = "wakeGestureEnabled"
    const val KEY_FORMATTING = "formattingEnabled"
}

/**
 * The appearance block: the three choices as cells, the stored one selected, and
 * the line that says what the system choice means.
 */
internal fun themeBlock(theme: ThemeState): Box = Box(
    id = SettingsIds.THEME_BLOCK,
    background = PaletteSlot.SURFACE,
    children = listOf(
        sectionLabel(SettingsIds.THEME_HEADING, SettingsTexts.APPEARANCE_HEADING),
        SegmentedRow(
            id = SettingsIds.THEME_CELLS,
            cells = listOf(
                themeCell(SettingsIds.THEME_SYSTEM, SettingsTexts.SEGMENT_SYSTEM, StoredThemeMode.SYSTEM, theme),
                themeCell(SettingsIds.THEME_LIGHT, SettingsTexts.SEGMENT_LIGHT, StoredThemeMode.LIGHT, theme),
                themeCell(SettingsIds.THEME_DARK, SettingsTexts.SEGMENT_DARK, StoredThemeMode.DARK, theme),
            ),
        ),
        bodyLabel(SettingsIds.THEME_HELP, SettingsTexts.APPEARANCE_SYSTEM_HELP, PaletteSlot.TEXT_MUTED),
    ),
)

/** The routing block: the three modes as cells, the one in force selected, and the line on automatic routing. */
internal fun routingBlock(held: AppSettings): Box = Box(
    id = SettingsIds.ROUTING_BLOCK,
    background = PaletteSlot.SURFACE,
    children = listOf(
        sectionLabel(SettingsIds.ROUTING_HEADING, SettingsTexts.ROUTING_HEADING),
        SegmentedRow(
            id = SettingsIds.ROUTING_CELLS,
            cells = SttMode.entries.map { routingCell(it, held) },
        ),
        bodyLabel(SettingsIds.ROUTING_HELP, SettingsTexts.ROUTING_AUTO_HELP, PaletteSlot.TEXT_MUTED),
    ),
)

/** The dictation block: a heading and the three switches, each with its label, its line and its state. */
internal fun switchesBlock(held: AppSettings): Box = Box(
    id = SettingsIds.SWITCHES_BLOCK,
    background = PaletteSlot.SURFACE,
    children = listOf(
        sectionLabel(SettingsIds.SWITCHES_HEADING, SettingsTexts.DICTATION_HEADING),
        switchRow(
            SettingsIds.SWITCH_PRELOAD,
            SettingsTexts.PRELOAD_LABEL,
            SettingsTexts.PRELOAD_HELP,
            SettingsIds.KEY_PRELOAD,
            held.preloadModel,
        ),
        switchRow(
            SettingsIds.SWITCH_WAKE,
            SettingsTexts.WAKE_LABEL,
            SettingsTexts.WAKE_HELP,
            SettingsIds.KEY_WAKE_GESTURE,
            held.wakeGestureEnabled,
        ),
        switchRow(
            SettingsIds.SWITCH_FORMATTING,
            SettingsTexts.FORMATTING_LABEL,
            SettingsTexts.FORMATTING_HELP,
            SettingsIds.KEY_FORMATTING,
            held.formattingEnabled,
        ),
    ),
)

/**
 * The model and server block: the speech model as a row, the server address as a
 * field, and the API key as a row that says only whether a key is held.
 */
internal fun valuesBlock(held: AppSettings): Box = Box(
    id = SettingsIds.VALUES_BLOCK,
    background = PaletteSlot.SURFACE,
    children = listOf(
        sectionLabel(SettingsIds.VALUES_HEADING, SettingsTexts.VALUES_HEADING),
        Box(
            id = SettingsIds.MODEL_ROW,
            background = PaletteSlot.SURFACE,
            horizontal = true,
            children = listOf(
                bodyLabel(SettingsIds.MODEL_LABEL, SetupTexts.HEADING_MODEL, PaletteSlot.TEXT),
                bodyLabel(
                    SettingsIds.MODEL_VALUE,
                    "${languageName(held.language)}, ${held.modelSize}",
                    PaletteSlot.TEXT,
                ),
            ),
        ),
        Box(
            id = SettingsIds.SERVER_ROW,
            background = PaletteSlot.SURFACE,
            children = listOf(
                bodyLabel(SettingsIds.SERVER_LABEL, SettingsTexts.SERVER_ROW_LABEL, PaletteSlot.TEXT),
                ValueField(
                    id = SettingsIds.SERVER_FIELD,
                    text = serverText(held),
                    muted = !held.hasServerUrl,
                ),
            ),
        ),
        Box(
            id = SettingsIds.API_KEY_ROW,
            background = PaletteSlot.SURFACE,
            horizontal = true,
            children = listOf(
                bodyLabel(SettingsIds.API_KEY_LABEL, SettingsTexts.API_KEY_LABEL, PaletteSlot.TEXT),
                bodyLabel(SettingsIds.API_KEY_VALUE, keyHeldText(held), PaletteSlot.TEXT),
            ),
        ),
    ),
)

/** One appearance cell: its words, the mode it asks for by name, and whether it is the stored choice. */
private fun themeCell(id: String, text: String, mode: StoredThemeMode, theme: ThemeState): SegmentCell =
    SegmentCell(
        id = id,
        text = text,
        selected = theme.stored == mode,
        intent = ScreenIntent.SetThemeMode(mode.name),
    )

/** One routing cell: the mode's words, the mode it asks for by name, and whether it is the mode in force. */
private fun routingCell(mode: SttMode, held: AppSettings): SegmentCell = SegmentCell(
    id = SettingsIds.ROUTING_CELL_PREFIX + mode.name,
    text = routingWord(mode),
    selected = held.mode == mode,
    intent = ScreenIntent.SetRoutingMode(mode.name),
)

private fun routingWord(mode: SttMode): String = when (mode) {
    SttMode.AUTO -> SettingsTexts.ROUTING_AUTO
    SttMode.LOCAL -> SettingsTexts.ROUTING_PHONE
    SttMode.SERVER -> SettingsTexts.ROUTING_SERVER
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

/**
 * The stored server address, or the words for none.
 *
 * Whitespace is not an address, so a field holding only spaces reads the same as an
 * empty one.
 */
private fun serverText(held: AppSettings): String =
    if (held.hasServerUrl) held.serverUrl else SettingsTexts.NOT_SET_TEXT

/**
 * Whether a key is held, and nothing about which one.
 *
 * An empty reference is still a reference: the credential behind it is held, so
 * the line says the same for an empty reference as for a filled one.
 */
private fun keyHeldText(held: AppSettings): String =
    if (held.apiKeyRef != null) SettingsTexts.HELD_TEXT else SettingsTexts.NOT_HELD_TEXT

private fun sectionLabel(id: String, text: String): Label = bodyLabel(id, text, PaletteSlot.TEXT_MUTED)

private fun bodyLabel(id: String, text: String, color: PaletteSlot): Label =
    Label(id = id, text = text, role = TypeRole.BODY, color = color)
