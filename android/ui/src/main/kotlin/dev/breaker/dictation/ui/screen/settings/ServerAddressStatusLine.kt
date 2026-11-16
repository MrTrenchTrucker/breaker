package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The one line about the state of the stored server addresses.
 *
 * The app computes the state and hands it in as a value; this file only turns a
 * value into words. There is deliberately ONE value rather than two readings of
 * the same thing, because two answers could disagree and the screen would then
 * have to choose which one to believe.
 */

/** What the stored server addresses were last seen to be doing. */
sealed class ServerAddressStatus {
    /** Nothing has been measured yet; the addresses are being checked. */
    object Checking : ServerAddressStatus()

    /** [address] answered, so the app will use it. */
    data class InUse(val address: String) : ServerAddressStatus()

    /** Every stored address was tried and none of them answered. */
    object NoneAnswered : ServerAddressStatus()
}

/** The node id of the status line; the gates find the line by it. */
internal const val SERVER_ADDRESS_STATUS_LINE_ID = "${SettingsIds.SERVER_ROW}.status"

/**
 * The status line for [status] over the stored [addresses], or null when there is nothing to say.
 *
 * With no stored address there is no state to report, so the line is left out entirely. Whether an
 * address is set is decided here, from the list the screen already holds, and never by reading
 * `settings.serverUrl`: a blank draft row is not an address either way.
 */
internal fun serverAddressStatusLine(status: ServerAddressStatus?, addresses: List<String>): Label? {
    if (status == null || addresses.none { it.isNotBlank() }) return null
    return Label(
        id = SERVER_ADDRESS_STATUS_LINE_ID,
        text = serverAddressStatusText(status),
        role = TypeRole.BODY,
        color = PaletteSlot.TEXT_MUTED,
    )
}

/** The words for one [status]. */
private fun serverAddressStatusText(status: ServerAddressStatus): String = when (status) {
    ServerAddressStatus.Checking -> SettingsTexts.SERVER_STATUS_CHECKING
    is ServerAddressStatus.InUse -> SettingsTexts.SERVER_IN_USE_LEAD + status.address + "."
    ServerAddressStatus.NoneAnswered -> SettingsTexts.SERVER_STATUS_NONE_ANSWERED
}
