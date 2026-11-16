package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings

/*
 * The ordered server address rows of the settings screen.
 *
 * The stored rows ARE the stored list, entry 1 first, because the list order is
 * the probe priority: the screen never sorts and never reorders on save. Row 1
 * keeps the stored key [ServerAddressEntry.KEY] and the node ids the
 * single-address screen always drew, so the first row is the same field that
 * screen showed.
 *
 * A row the person adds is a DRAFT of the screen, not a stored address: the
 * screen holds a row count, the stored rows are padded with blanks up to it and
 * cut to it, and nothing is written until the person fills the row. A draft
 * row that is never filled therefore does not survive a reload, which is the
 * intended meaning of a blank entry. [SERVER_ROW_LIMIT] is the most rows the
 * screen offers; the add control is disabled at it, and remove is disabled on
 * the last remaining row.
 */

/** The most server address rows the settings screen shows. */
internal const val SERVER_ROW_LIMIT = 3

/** The stored key of each row, entry 1 first; the same names the settings file uses. */
internal val SERVER_ROW_KEYS: List<String> =
    listOf(ServerAddressEntry.KEY, "server_address_2", "server_address_3")

/** The row texts [held] stores, entry 1 first; at least the one row of a blank install. */
internal fun serverAddressRows(held: AppSettings): List<String> =
    held.serverAddressList().ifEmpty { listOf("") }

/** The row count of [held]: the stored list size, at least the one row of a blank install, at most [SERVER_ROW_LIMIT]. */
internal fun storedRowCount(held: AppSettings): Int =
    serverAddressRows(held).size.coerceIn(1, SERVER_ROW_LIMIT)

/** The row texts to draw for a draft count of [count]: the stored rows, padded with blanks and cut to [count]. */
internal fun serverRows(held: AppSettings, count: Int): List<String> {
    val rows = serverAddressRows(held)
    return List(count.coerceAtLeast(1)) { rows.getOrElse(it) { "" } }
}

/** The stored key of row [index], or null when the screen has no such row. */
internal fun serverRowKey(index: Int): String? = SERVER_ROW_KEYS.getOrNull(index)

/** The node id of row [index]'s field: the first row keeps the id the screen always used. */
internal fun serverRowFieldId(index: Int): String =
    if (index == 0) SettingsIds.SERVER_FIELD else "${SettingsIds.SERVER_FIELD}.${index + 1}"

/** The node id of row [index]'s plain-http line. */
internal fun serverRowPlainHttpId(index: Int): String =
    if (index == 0) SettingsIds.PLAIN_HTTP_LINE else "${SettingsIds.PLAIN_HTTP_LINE}.${index + 1}"

/** The node id of row [index]'s control named [action]. */
internal fun serverRowActionId(index: Int, action: String): String =
    "${SettingsIds.SERVER_ROW}.$action.${index + 1}"

/** The node id of the add control under the rows. */
internal const val SERVER_ROW_ADD_ID = "settings.server.add"

/**
 * The draft row count after adding one row: one more than [current], never past
 * [SERVER_ROW_LIMIT].
 *
 * Adding a row writes nothing to [AppSettings]; the count is the screen's own
 * state and the new row is drawn as an empty field.
 */
internal fun addServerAddressRow(current: Int): Int = (current + 1).coerceAtMost(SERVER_ROW_LIMIT)

/**
 * The draft row count after removing one row: one less than [current], never
 * below the one row a blank install keeps.
 *
 * Removing a row writes nothing to [AppSettings]; the count is the screen's own
 * state and the last drawn row is the one that goes.
 */
internal fun removeServerAddressRow(current: Int): Int = (current - 1).coerceAtLeast(1)

/**
 * The change of row [index] to [typed], or null when the text is refused by the
 * address rule.
 *
 * The stored list is [ServerAddressEntry.acceptedList] of the rows with row
 * [index] replaced, so the other rows are kept in their order and a blank row
 * that is last is dropped. The change writes [AppSettings.serverUrl] and
 * [AppSettings.serverAddresses] together: the model refuses a list whose first
 * entry is not [AppSettings.serverUrl], so setting either field alone throws.
 * A list the rule refuses whole throws [IllegalArgumentException], which the
 * writer reads as a refusal and the screen answers with the address notice.
 */
internal fun serverAddressRowEdit(index: Int, typed: String): ((AppSettings) -> AppSettings)? {
    if (index !in SERVER_ROW_KEYS.indices) return null
    val stored = ServerAddressEntry.accepted(typed) ?: return null
    return { held ->
        val kept = keptRows(held, index, stored)
        held.copy(serverUrl = kept.firstOrNull().orEmpty(), serverAddresses = canonicalRows(kept))
    }
}

/**
 * The canonical stored list: two or more entries, or empty.
 *
 * A single address is `serverUrl` alone — the same shape the codec decodes a legacy
 * one-address file to, and the shape every reader already handles through
 * `AppSettings.serverAddressList()`. Keeping one entry in the list as well would
 * make an edit of the only row produce an AppSettings that is not equal to the
 * settings it was loaded from.
 */
private fun canonicalRows(rows: List<String>): List<String> = if (rows.size >= 2) rows else emptyList()

/** The stored list with row [index] set to [stored]; a refused list throws. */
private fun keptRows(held: AppSettings, index: Int, stored: String): List<String> {
    val rows = serverAddressRows(held).toMutableList()
    while (rows.size <= index) rows.add("")
    rows[index] = stored
    // A blank row means "not set", exactly as a blank install decodes: blank rows are
    // removed before the rule runs. So clearing row 1 of two entries promotes the
    // second to entry 1 instead of refusing the whole list, clearing the only row
    // clears the address, and a typed row past the stored count lands at the end.
    val kept = ServerAddressEntry.acceptedList(rows.filter { it.isNotBlank() })
    if (kept.isEmpty() && rows.any { it.isNotBlank() }) {
        throw IllegalArgumentException("the server address list is refused")
    }
    return kept
}

/** The change that moves row [index] one place up (towards entry 1) or down. */
internal fun moveServerAddressRow(index: Int, up: Boolean): (AppSettings) -> AppSettings = { held ->
    val rows = serverAddressRows(held).toMutableList()
    val target = if (up) index - 1 else index + 1
    if (index in rows.indices && target in rows.indices) {
        rows.add(target, rows.removeAt(index))
    }
    held.copy(serverUrl = rows.first(), serverAddresses = canonicalRows(rows))
}
