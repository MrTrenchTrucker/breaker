package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.controls.ControlTexts
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.SegmentCell
import dev.breaker.dictation.ui.screen.SegmentedRow
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.SwitchRow
import dev.breaker.dictation.ui.screen.TextAlign
import dev.breaker.dictation.ui.screen.TrimStripe
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.ThemeState

/*
 * The blocks of the settings screen, each one a node built from the values
 * it is handed and from nothing else.
 *
 * The blocks are flat: each one sits on the page background, with no card fill.
 * Headings are capitals in the muted slot, help lines are muted body text, and
 * values are in the text slot. Spacing is named by size. No function here names
 * a colour of its own.
 */

/** The node ids and the stored keys of the settings screen, in one place. */
internal object SettingsIds {
    const val TITLE_ID = "settings.title"
    const val STRIPE_ID = "settings.stripe"
    const val NOTICE_ID = "settings.notice"

    const val GAP_HEAD = "settings.gap.head"
    const val GAP_ROUTING = "settings.gap.routing"
    const val GAP_SWITCHES = "settings.gap.switches"
    const val GAP_VALUES = "settings.gap.values"

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
    const val HAIRLINE_PRELOAD = "settings.hairline.preload"
    const val HAIRLINE_WAKE = "settings.hairline.wake"

    const val VALUES_BLOCK = "settings.values"
    const val VALUES_HEADING = "settings.values.heading"
    const val MODEL_ROW = "settings.model"
    const val MODEL_HEAD = "settings.model.head"
    const val MODEL_LABEL = "settings.model.label"
    const val MODEL_READY = "settings.model.ready"
    const val MODEL_READY_LABEL = "settings.model.ready.label"
    const val MODEL_VALUE = "settings.model.value"
    const val MODEL_LINE = "settings.model.line"
    const val DOWNLOAD_ID = "settings.model.download"
    const val DOWNLOAD_ACTION = "download-speech-model"
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
 * The appearance block: a heading, the three choices as cells with the stored one
 * selected, and the line that says what the system choice means.
 */
internal fun themeBlock(theme: ThemeState): Box = Box(
    id = SettingsIds.THEME_BLOCK,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
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

/** The routing block: a heading, the three modes as cells with the one in force selected, and the line on automatic routing. */
internal fun routingBlock(held: AppSettings): Box = Box(
    id = SettingsIds.ROUTING_BLOCK,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
    children = listOf(
        sectionLabel(SettingsIds.ROUTING_HEADING, SettingsTexts.ROUTING_HEADING),
        SegmentedRow(
            id = SettingsIds.ROUTING_CELLS,
            cells = SttMode.entries.map { routingCell(it, held) },
        ),
        bodyLabel(SettingsIds.ROUTING_HELP, SettingsTexts.ROUTING_AUTO_HELP, PaletteSlot.TEXT_MUTED),
    ),
)

/** The dictation block: a heading and the three switches, with a hairline between each two rows. */
internal fun switchesBlock(held: AppSettings): Box = Box(
    id = SettingsIds.SWITCHES_BLOCK,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
    children = listOf(
        sectionLabel(SettingsIds.SWITCHES_HEADING, SettingsTexts.DICTATION_HEADING),
        switchRow(
            SettingsIds.SWITCH_PRELOAD,
            SettingsTexts.PRELOAD_LABEL,
            SettingsTexts.PRELOAD_HELP,
            SettingsIds.KEY_PRELOAD,
            held.preloadModel,
        ),
        TrimStripe(SettingsIds.HAIRLINE_PRELOAD),
        switchRow(
            SettingsIds.SWITCH_WAKE,
            SettingsTexts.WAKE_LABEL,
            SettingsTexts.WAKE_HELP,
            SettingsIds.KEY_WAKE_GESTURE,
            held.wakeGestureEnabled,
        ),
        TrimStripe(SettingsIds.HAIRLINE_WAKE),
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
 * The model and server block: the speech model row, the server address as a
 * field, and the API key as a row that says only whether a key is held.
 */
internal fun valuesBlock(held: AppSettings, model: ModelRow): Box = Box(
    id = SettingsIds.VALUES_BLOCK,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
    children = listOf(
        sectionLabel(SettingsIds.VALUES_HEADING, SettingsTexts.VALUES_HEADING),
        modelRow(held, model),
        Box(
            id = SettingsIds.SERVER_ROW,
            background = PaletteSlot.BACKGROUND,
            gap = Space.S,
            children = listOf(
                bodyLabel(SettingsIds.SERVER_LABEL, SettingsTexts.SERVER_ROW_LABEL, PaletteSlot.TEXT_MUTED),
                ValueField(
                    id = SettingsIds.SERVER_FIELD,
                    text = serverText(held),
                    muted = !held.hasServerUrl,
                ),
            ),
        ),
        Box(
            id = SettingsIds.API_KEY_ROW,
            background = PaletteSlot.BACKGROUND,
            horizontal = true,
            spread = true,
            children = listOf(
                bodyLabel(SettingsIds.API_KEY_LABEL, SettingsTexts.API_KEY_LABEL, PaletteSlot.TEXT),
                monoLabel(SettingsIds.API_KEY_VALUE, keyHeldText(held), PaletteSlot.TEXT_MUTED, TextAlign.END),
            ),
        ),
    ),
)

/**
 * The speech model row: the label with the READY badge when the model is ready, the
 * language and size in mono, and, when it is not ready, the download button with the
 * line about a download in progress.
 */
private fun modelRow(held: AppSettings, model: ModelRow): Box = Box(
    id = SettingsIds.MODEL_ROW,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
    children = buildList<Node> {
        add(modelHead(model))
        add(monoLabel(SettingsIds.MODEL_VALUE, "${languageName(held.language)}, ${held.modelSize}", PaletteSlot.TEXT_MUTED))
        if (!model.ready) {
            add(
                Action(
                    id = SettingsIds.DOWNLOAD_ID,
                    text = SetupTexts.BUTTON_DOWNLOAD_MODEL,
                    emphasis = Emphasis.PRIMARY,
                    intent = ScreenIntent.Setup(SettingsIds.DOWNLOAD_ACTION),
                    kit = KitLook.RAISED,
                ),
            )
            model.line?.let { add(bodyLabel(SettingsIds.MODEL_LINE, it, PaletteSlot.TEXT_MUTED)) }
        }
    },
)

/** The row above the mono value: the label at the left, and the READY badge at the right edge when the model is ready. */
private fun modelHead(model: ModelRow): Box = Box(
    id = SettingsIds.MODEL_HEAD,
    background = PaletteSlot.BACKGROUND,
    horizontal = true,
    spread = true,
    children = buildList<Node> {
        add(bodyLabel(SettingsIds.MODEL_LABEL, SetupTexts.HEADING_MODEL, PaletteSlot.TEXT))
        if (model.ready) add(readyBadge())
    },
)

/** The READY badge: the existing word in capitals, in the on colour, on a primary fill. */
private fun readyBadge(): Box = Box(
    id = SettingsIds.MODEL_READY,
    background = PaletteSlot.PRIMARY,
    pad = Space.XS,
    children = listOf(
        Label(
            id = SettingsIds.MODEL_READY_LABEL,
            text = ControlTexts.BADGE_READY,
            role = TypeRole.BODY,
            color = PaletteSlot.ON,
            align = TextAlign.CENTER,
            caps = true,
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

/** A heading: capitals in the display role, in the muted slot. */
private fun sectionLabel(id: String, text: String): Label =
    Label(id = id, text = text, role = TypeRole.DISPLAY, color = PaletteSlot.TEXT_MUTED, caps = true)

private fun bodyLabel(id: String, text: String, color: PaletteSlot): Label =
    Label(id = id, text = text, role = TypeRole.BODY, color = color)

private fun monoLabel(id: String, text: String, color: PaletteSlot, align: TextAlign = TextAlign.START): Label =
    Label(id = id, text = text, role = TypeRole.MONO, color = color, align = align)
