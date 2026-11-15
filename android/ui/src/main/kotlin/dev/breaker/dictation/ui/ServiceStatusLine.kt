package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.screen.home.ServiceStatusEntry
import dev.breaker.dictation.ui.screen.home.ServiceStatusSentence
import dev.breaker.dictation.ui.screen.home.ServiceStatusTexts
import dev.breaker.dictation.ui.screen.home.decideServiceStatus

/**
 * The one service-status line, and the seam the app's answer crosses into this module's words.
 *
 * The app cannot name [ServiceStatusSentence], [ServiceStatusEntry] or `decideServiceStatus` (they are
 * internal to this module), and this module does not depend on the api-contracts module, so it cannot
 * name the app's answer type or the shared reply either. The app therefore hands the reply's names and
 * up/down as plain pairs, plus whether the ask itself failed and whether it asked at all; the decision
 * and the words stay here, so the app never writes the line.
 *
 * Null when there is no line: not asked, or a reply that lists nothing to report. Never an empty
 * string and never a placeholder.
 */
internal fun serviceStatusLine(
    services: List<Pair<String, Boolean>>?,
    failed: Boolean,
    asked: Boolean,
): String? {
    val reply = services?.map { ServiceStatusEntry(it.first, it.second) }
    return when (decideServiceStatus(reply, failed, asked)) {
        ServiceStatusSentence.NONE -> null
        ServiceStatusSentence.ALL_UP -> ServiceStatusTexts.ALL_UP
        ServiceStatusSentence.TRANSCRIPTION_DOWN -> ServiceStatusTexts.TRANSCRIPTION_DOWN
        ServiceStatusSentence.SERVER_UNREACHABLE -> ServiceStatusTexts.SERVER_UNREACHABLE
    }
}
