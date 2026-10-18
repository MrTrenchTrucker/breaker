package dev.breaker.dictation.host

/**
 * Holds the sentence of a refused foreground until the launcher activity shows it. It lives in memory only:
 * [set] keeps the sentence, and [take] returns it once and clears it, so a second take finds nothing. The
 * service and the activity both run on the main call path, so no lock is used.
 */
internal class RefusalHold {
    private var held: String? = null

    /** Keeps [sentence] until the next [take]. A later set replaces it. */
    fun set(sentence: String) {
        held = sentence
    }

    /** Returns the held sentence, or null when there is none, and clears the hold. */
    fun take(): String? {
        val value = held
        held = null
        return value
    }
}

/**
 * What a refused foreground leaves behind. [refuse] first keeps the sentence in the hold, then asks whether
 * notifications are permitted, and posts a notice only when they are. Each step is guarded: a step that fails
 * is skipped, the next one still runs, and nothing escapes, so the service can still switch off and stop.
 *
 * @param hold keeps the sentence for the launcher activity.
 * @param notificationPermitted answers whether the user lets the app post notifications.
 * @param postNotice posts the notice with the sentence as its text.
 */
internal class ServiceRefusalNotice(
    private val hold: (String) -> Unit,
    private val notificationPermitted: () -> Boolean,
    private val postNotice: (String) -> Unit,
) {

    /** Keeps [sentence] for the launcher activity and posts it as a notice when notifications are permitted. */
    fun refuse(sentence: String) {
        try {
            hold(sentence)
        } catch (failure: Exception) {
        }
        val permitted = try {
            notificationPermitted()
        } catch (failure: Exception) {
            false
        }
        if (!permitted) return
        try {
            postNotice(sentence)
        } catch (failure: Exception) {
        }
    }
}
