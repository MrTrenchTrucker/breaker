package dev.breaker.dictation.transport

/**
 * The pure order-and-fallback policy for probing a stored list of Local Server addresses.
 *
 * One call answers "which address should the app use now?": the [entries] are asked in LIST
 * ORDER, entry 1 first, and the first entry whose [answers] predicate is true is returned; the
 * entries after it are not asked. When none answers, [lastGood] is returned when it is not
 * blank, then entry 1, then the empty string.
 *
 * **The last good address is never tried ahead of the list.** It is the address the app starts
 * with before the first probe finishes and the fallback when no entry answers, never a head
 * start: a phone that came back home must return to the home address even when the road address
 * still answers, so list order is the whole of the preference and [lastGood] is only ever
 * RETURNED, after every entry has been asked and failed.
 *
 * Pure: no sockets, no clock, no state. The caller owns the probe behind [answers] and the
 * persistence of the last good address; this object only decides the order and the fallback.
 *
 * @param entries the configured addresses in priority order, entry 1 first.
 * @param lastGood the address that last answered, or blank when none ever did.
 * @param answers the reachability predicate for one address. It is called at most once per
 *   entry, in list order, and never for an entry after one has answered true.
 */
object ProbeSelection {
    fun select(entries: List<String>, lastGood: String, answers: (String) -> Boolean): String {
        for (entry in entries) {
            if (answers(entry)) return entry
        }
        if (lastGood.isNotBlank()) return lastGood
        return entries.firstOrNull() ?: ""
    }
}
