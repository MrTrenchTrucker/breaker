package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Box
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.Space
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The server address rows of the settings screen, drawn from the settings and
 * from nothing else.
 *
 * The rows are the stored list, entry 1 first, padded with blank draft rows up
 * to the count the screen holds and cut to it. The list order is the probe
 * priority, so the screen never sorts and never reorders. A draft row is an
 * empty field, not a stored address: it is drawn, and it is written only when
 * the person fills it.
 */

/**
 * The server address block: the block label, then each row in stored order with
 * its own field, its plain-http line when it is plain http and its remove and
 * move controls, and last the add control.
 */
internal fun serverAddressBlock(held: AppSettings, rowCount: Int): Box = Box(
    id = SettingsIds.SERVER_ROW,
    background = PaletteSlot.BACKGROUND,
    gap = Space.S,
    children = buildList<Node> {
        add(bodyLabel(SettingsIds.SERVER_LABEL, SettingsTexts.SERVER_ROW_LABEL, PaletteSlot.TEXT_MUTED))
        val rows = serverRows(held, rowCount)
        rows.forEachIndexed { index, stored ->
            add(
                ValueField(
                    id = serverRowFieldId(index),
                    text = rowText(stored),
                    muted = stored.isBlank(),
                    entryKey = serverRowKey(index),
                ),
            )
            if (ServerAddressEntry.isPlainHttp(stored)) {
                add(
                    bodyLabel(
                        serverRowPlainHttpId(index),
                        SettingsTexts.PLAIN_HTTP_HELP,
                        PaletteSlot.TEXT_MUTED,
                    ),
                )
            }
            add(serverRowControls(index, rows.size))
        }
        add(
            Action(
                id = SERVER_ROW_ADD_ID,
                text = SettingsTexts.SERVER_ROW_ADD,
                enabled = rows.size < SERVER_ROW_LIMIT,
                intent = ScreenIntent.AddServerAddress(rows.size),
                kit = KitLook.OUTLINED,
            ),
        )
    },
)

/**
 * The remove and move controls of row [index]: remove is unavailable while one
 * row remains, move up on row 1, and move down on the last row.
 */
private fun serverRowControls(index: Int, rowCount: Int): Box = Box(
    id = "${SettingsIds.SERVER_ROW}.actions.${index + 1}",
    background = PaletteSlot.BACKGROUND,
    horizontal = true,
    gap = Space.XS,
    children = listOf(
        rowAction(
            serverRowActionId(index, SettingsIds.SERVER_ACTION_REMOVE),
            SettingsTexts.SERVER_ROW_REMOVE,
            ScreenIntent.RemoveServerAddress(index),
            enabled = rowCount > 1,
        ),
        rowAction(
            serverRowActionId(index, SettingsIds.SERVER_ACTION_MOVE_UP),
            SettingsTexts.SERVER_ROW_MOVE_UP,
            ScreenIntent.MoveServerAddressUp(index),
            enabled = index > 0,
        ),
        rowAction(
            serverRowActionId(index, SettingsIds.SERVER_ACTION_MOVE_DOWN),
            SettingsTexts.SERVER_ROW_MOVE_DOWN,
            ScreenIntent.MoveServerAddressDown(index),
            enabled = index < rowCount - 1,
        ),
    ),
)

/** One server row control: its words, the row action it reports, and whether it is available. */
private fun rowAction(id: String, text: String, intent: ScreenIntent, enabled: Boolean): Action =
    Action(id = id, text = text, enabled = enabled, intent = intent, kit = KitLook.OUTLINED)

/**
 * The words a row shows for the stored text [stored].
 *
 * Whitespace is not an address, so a blank row, a draft one or a cleared one,
 * reads the same as an empty one.
 */
private fun rowText(stored: String): String =
    if (stored.isBlank()) SettingsTexts.NOT_SET_TEXT else stored

/** A heading or help line of the block: body text in one palette slot. */
private fun bodyLabel(id: String, text: String, color: PaletteSlot) =
    Label(id = id, text = text, role = TypeRole.BODY, color = color)
