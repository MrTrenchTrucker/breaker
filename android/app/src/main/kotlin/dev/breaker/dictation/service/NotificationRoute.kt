package dev.breaker.dictation.service

/**
 * Where a tap on the ongoing notification takes the user.
 *
 * The tap opens the launcher activity with [EXTRA_ROUTE] set to [ROUTE_HISTORY]. On a fresh create the
 * launcher shows the history screen when the route is [ROUTE_HISTORY] (see [opensHistory]). The notification that
 * says the speech model is missing carries [ROUTE_MODEL]; the launcher puts the download button in focus.
 */
object NotificationRoute {
    /** The name of the intent extra that carries the route. */
    const val EXTRA_ROUTE: String = "dev.breaker.dictation.extra.ROUTE"

    /** The route that shows the history of transcriptions. */
    const val ROUTE_HISTORY: String = "history"

    /** The route that leads to the speech model download. */
    const val ROUTE_MODEL: String = "model"

    /** The full class name of the launcher activity the tap opens; it is the activity the manifest declares. */
    const val TARGET_ACTIVITY: String = "dev.breaker.dictation.SettingsLauncherActivity"

    /** True when the route asks the launcher to show the history screen; the match is exact and case sensitive. */
    fun opensHistory(route: String?): Boolean = route == ROUTE_HISTORY
}
